package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.kafka.KafkaClients;
import dgm.kit.outbox.EnvelopeBuilder;
import dgm.kit.outbox.OutboxRow;
import dgm.kit.outbox.OutgoingEvent;
import dgm.kit.testing.Stand;
import dgm.kit.trace.TraceContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Товар с выдачей по API на стенде (шаг 8 среза 1 Ф5; ADR-008, Р-183, Р-192, SM-08, INV-05, INV-09): строка остатка по событиям каталога,
 * правка продавцом через шлюз по {@code If-Match} (412, 428, 409, 404, 422), резерв по количеству без ключей, подтверждение, снятие
 * по сроку и по {@code order.cancelled} (в том числе использованного резерва, T5), версии {@code stock.changed} без пропусков и ограничения
 * базы (CHECK остатка, триггеры {@code api_stock_guard} и {@code reservation_used_release_guard}). Сервис работает в процессе теста, база,
 * Kafka и Redis это набор {@code dev-kit} (основа {@link ReservationStandIT}). Гонки резерва и правки войдут в серию конкуренции шага 10.
 */
class ApiStockIT extends ReservationStandIT {

    private static KafkaProducer<String, String> catalog;

    @BeforeAll
    static void catalogClient() {
        catalog = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("catalog-service"), "catalog-service-it-api");
    }

    @AfterAll
    static void closeCatalogClient() {
        catalog.close(Duration.ofSeconds(5));
    }

    // ---------------------------------------------------------------- помощники

    /** Публикует событие товара от имени каталога: права Kafka выданы по CN сертификата. Ключ записи равен идентификатору товара. */
    private static void publishProduct(String type, UUID productId, Map<String, Object> data) throws Exception {
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"" + TraceContext.fresh().traceparent() + "\", \"correlationid\": \""
                + UUID.randomUUID() + "\"}";
        OutgoingEvent event = new EnvelopeBuilder("catalog-service").build(new OutboxRow(1, UUID.randomUUID(), "Product", productId.toString(), type,
                "catalog.events", Json.write(data), headers, Instant.now(), 0));
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        catalog.send(record).get(20, TimeUnit.SECONDS);
    }

    private static Map<String, Object> productData(UUID productId, UUID sellerId, String issuanceMethod, int version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId.toString());
        data.put("sellerId", sellerId.toString());
        data.put("issuanceMethod", issuanceMethod);
        data.put("status", "draft");
        data.put("version", version);
        return data;
    }

    private static Integer sourceVersion(UUID productId) {
        List<Integer> rows = db.queryForList("select source_version from inventory.product_copy where product_id = ?", Integer.class, productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> apiRow(UUID productId) {
        List<Map<String, Object>> rows = db.queryForList("select quantity, version from inventory.api_stock where product_id = ?", productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static int apiQuantity(UUID productId) {
        return ((Number) apiRow(productId).get("quantity")).intValue();
    }

    private static int apiVersion(UUID productId) {
        return ((Number) apiRow(productId).get("version")).intValue();
    }

    private static Response put(Seller seller, UUID productId, String ifMatch, String body) throws IOException {
        String[] headers = ifMatch == null ? seller.auth : with(seller.auth, "If-Match", ifMatch);
        return gateway.request("PUT", PRODUCTS + "/" + productId + "/api-stock", "application/json", body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private static String quantity(int value) {
        return "{\"quantity\":" + value + "}";
    }

    /** Товар по API с заданным остатком: правка продавцом по версии, как в кабинете. */
    private static UUID productWith(Seller seller, int quantity) throws IOException {
        UUID productId = seller.product("published", "seller_api");
        Response set = put(seller, productId, "\"1\"", quantity(quantity));
        assertEquals(200, set.status(), set.body());
        return productId;
    }

    private static List<Object> available(UUID productId) {
        return stockEvents(productId).stream().map(e -> e.get("available")).toList();
    }

    // ---------------------------------------------------------------- строка остатка и загрузка ключей (TC-415)

    @Tag("TC-415")
    @Test
    void theCatalogEventCreatesTheStockRowAndLaterEventsKeepTheQuantity() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        publishProduct("product.created", productId, productData(productId, sellerId, "seller_api", 1));
        assertTrue(await(Duration.ofSeconds(30), () -> apiRow(productId) != null), "строка остатка создана вместе с копией товара");
        assertEquals(0, apiQuantity(productId), "до правки продавцом заказать нечего (INV-09)");
        assertEquals(1, apiVersion(productId));

        db.update("update inventory.api_stock set quantity = 9 where product_id = ?", productId);
        assertEquals(2, apiVersion(productId), "триггер поднимает версию на единицу при каждой правке");
        publishProduct("product.updated", productId, productData(productId, sellerId, "seller_api", 2));
        assertTrue(await(Duration.ofSeconds(30), () -> Integer.valueOf(2).equals(sourceVersion(productId))), "событие применено");
        assertEquals(9, apiQuantity(productId), "повтор события остаток не обнуляет (on conflict do nothing)");
        assertEquals(2, apiVersion(productId));
    }

    @Tag("TC-415")
    @Test
    void aKeyPoolProductGetsNoStockRowAndAnApiProductTakesNoKeys() throws Exception {
        UUID poolProduct = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        publishProduct("product.created", poolProduct, productData(poolProduct, sellerId, "key_pool", 1));
        assertTrue(await(Duration.ofSeconds(30), () -> sourceVersion(poolProduct) != null));
        assertNull(apiRow(poolProduct), "у товара с пулом остатка по API нет");

        Seller seller = new Seller();
        UUID productId = seller.product("published", "seller_api");
        Response batch = batch(seller, productId, key(), key());
        assertEquals(409, batch.status(), batch.body());
        assertEquals("issuance-method-mismatch", code(batch));
        Response file = file(seller, productId, key() + "\n" + key() + "\n");
        assertEquals(409, file.status(), file.body());
        assertEquals("issuance-method-mismatch", code(file));
        assertEquals(0, count(productId), "ключи товару по API не сохраняются");
    }

    @Tag("TC-415")
    @Test
    void theDatabaseKeepsStockRowsOnlyForApiProductsAndBumpsTheVersionItself() {
        Seller seller = new Seller();
        UUID poolProduct = seller.product("published", "key_pool");
        DataIntegrityViolationException foreign = assertThrows(DataIntegrityViolationException.class,
                () -> db.update("insert into inventory.api_stock (product_id, quantity) values (?, 5)", poolProduct));
        assertTrue(String.valueOf(foreign.getMessage()).contains("выдачей по API"), "товару с пулом строку остатка не завести");

        UUID productId = seller.product("published", "seller_api");
        db.update("insert into inventory.api_stock (product_id, quantity) values (?, 5)", productId);
        assertEquals(1, apiVersion(productId));
        db.update("update inventory.api_stock set quantity = quantity - 1 where product_id = ?", productId);
        db.update("update inventory.api_stock set quantity = quantity + 3 where product_id = ?", productId);
        assertEquals(3, apiVersion(productId), "версия растёт на единицу при каждой правке, даже если её не трогали");
        assertEquals(7, apiQuantity(productId));
        UUID other = seller.product("published", "seller_api");
        assertThrows(DataIntegrityViolationException.class, () -> db.update("update inventory.api_stock set product_id = ? where product_id = ?", other, productId));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("update inventory.api_stock set quantity = -1 where product_id = ?", productId));
        assertEquals(7, apiQuantity(productId), "CHECK не пускает остаток в минус (INV-09)");
    }

    // ---------------------------------------------------------------- setApiStock и getProductStock (TC-419)

    @Tag("TC-419")
    @Tag("TC-416")
    @Test
    void theSellerSetsTheStockByVersionAndSeesItInTheStockInfo() throws Exception {
        Seller seller = new Seller();
        UUID productId = seller.product("published", "seller_api");
        Response before = stock(seller, productId);
        assertEquals(200, before.status(), before.body());
        assertEquals("seller_api", before.json().get("issuanceMethod"));
        assertEquals(0, before.json().get("available"));
        assertEquals(0, before.json().get("reserved"));
        assertEquals(0, before.json().get("issued"));
        assertEquals(1, before.json().get("version"));
        assertNull(before.json().get("poolSize"), "у товара по API пула нет");
        assertNull(before.json().get("poolLimit"));

        Response set = put(seller, productId, "\"1\"", quantity(50));
        assertEquals(200, set.status(), set.body());
        assertEquals("\"2\"", set.header("ETag"));
        assertEquals("no-store", set.header("Cache-Control"));
        assertEquals(50, set.json().get("quantity"));
        assertEquals(2, set.json().get("version"));
        assertEquals(productId.toString(), set.json().get("productId"));
        assertNotNull(set.json().get("updatedAt"));
        assertEquals(50, apiQuantity(productId));
        assertEquals(2, apiVersion(productId));
        assertEquals(List.of(50), available(productId), "изменение количества публикует stock.changed ровно один раз");

        Response after = stock(seller, productId);
        assertEquals(50, after.json().get("available"));
        assertEquals(2, after.json().get("version"));
    }

    @Tag("TC-419")
    @Test
    void theIfMatchHeaderIsRequiredStrongAndCurrentAndTheSameValueChangesNothing() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 10);

        Response missing = put(seller, productId, null, quantity(20));
        assertEquals(428, missing.status(), missing.body());
        assertEquals("precondition-required", code(missing));
        for (String bad : List.of("2", "W/\"2\"", "\"x\"", "*")) {
            Response weak = put(seller, productId, bad, quantity(20));
            assertEquals(400, weak.status(), bad + ": " + weak.body());
        }
        Response stale = put(seller, productId, "\"1\"", quantity(20));
        assertEquals(412, stale.status(), stale.body());
        assertEquals("version-conflict", code(stale));
        assertEquals(10, stale.json().get("available"), "ответ называет текущий остаток");
        assertEquals("\"2\"", stale.header("ETag"), "и текущую версию, с которой повторять");
        assertEquals(10, apiQuantity(productId));
        assertEquals(2, apiVersion(productId));

        Response same = put(seller, productId, "\"2\"", quantity(10));
        assertEquals(200, same.status(), same.body());
        assertEquals("\"2\"", same.header("ETag"), "то же значение версию не двигает");
        assertEquals(2, apiVersion(productId));
        assertEquals(List.of(10), available(productId), "и событие не пишет");
    }

    @Tag("TC-419")
    @Test
    void anOrderThatArrivesWhileTheSellerEditsMakesTheEditStaleInsteadOfOverwritingIt() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 10);
        Response form = stock(seller, productId);
        assertEquals(2, form.json().get("version"), "продавец открыл форму с версией 2");

        assertEquals(201, reserve(UUID.randomUUID(), productId, 3).status());
        assertEquals(7, apiQuantity(productId));
        assertEquals(3, apiVersion(productId));

        Response stale = put(seller, productId, "\"2\"", quantity(20));
        assertEquals(412, stale.status(), stale.body());
        assertEquals(7, stale.json().get("available"));
        assertEquals("\"3\"", stale.header("ETag"));
        assertEquals(7, apiQuantity(productId), "заказ не затёрт правкой по устаревшей форме");

        Response fresh = put(seller, productId, stale.header("ETag"), quantity(20));
        assertEquals(200, fresh.status(), fresh.body());
        assertEquals(20, apiQuantity(productId));
        assertEquals(4, apiVersion(productId));
        assertEquals(List.of(10, 7, 20), available(productId));
    }

    @Tag("TC-419")
    @Test
    void foreignPoolBlockedProductsAndBadBodiesAreRefusedWithoutChangingAnything() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 10);

        Response foreign = put(new Seller(), productId, "\"2\"", quantity(5));
        assertEquals(404, foreign.status(), foreign.body());
        Response unknown = put(seller, UUID.randomUUID(), "\"1\"", quantity(5));
        assertEquals(404, unknown.status(), unknown.body());

        UUID poolProduct = seller.product("published", "key_pool");
        Response pool = put(seller, poolProduct, "\"1\"", quantity(5));
        assertEquals(409, pool.status(), pool.body());
        assertEquals("issuance-method-mismatch", code(pool));
        assertNull(apiRow(poolProduct), "товару с пулом строка остатка не создаётся");

        UUID blocked = seller.product("blocked", "seller_api");
        Response refused = put(seller, blocked, "\"1\"", quantity(5));
        assertEquals(409, refused.status(), refused.body());
        assertEquals("state-conflict", code(refused));
        assertEquals("blocked", refused.json().get("currentStatus"));

        for (int bad : new int[] {-1, 1_000_001}) {
            Response range = put(seller, productId, "\"2\"", quantity(bad));
            assertEquals(422, range.status(), bad + ": " + range.body());
        }
        assertEquals(422, put(seller, productId, "\"2\"", "{\"quantity\":\"7\"}").status());
        assertEquals(422, put(seller, productId, "\"2\"", "{\"quantity\":5,\"version\":2}").status());
        assertEquals(400, put(seller, productId, "\"2\"", "{\"quantity\":").status());
        Response text = gateway.request("PUT", PRODUCTS + "/" + productId + "/api-stock", "text/plain", "5".getBytes(StandardCharsets.UTF_8),
                with(seller.auth, "If-Match", "\"2\""));
        assertEquals(415, text.status(), text.body());
        Response anonymous = gateway.request("PUT", PRODUCTS + "/" + productId + "/api-stock", "application/json",
                quantity(5).getBytes(StandardCharsets.UTF_8), "If-Match", "\"2\"");
        assertEquals(401, anonymous.status(), anonymous.body());

        assertEquals(10, apiQuantity(productId));
        assertEquals(2, apiVersion(productId));
        assertEquals(List.of(10), available(productId));
    }

    // ---------------------------------------------------------------- резерв (TC-416)

    @Tag("TC-416")
    @Test
    void aReservationSubtractsTheQuantityHoldsNoKeysAndNeverGoesBelowZero() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 5);
        UUID orderId = UUID.randomUUID();
        Response reserved = reserve(orderId, productId, 3);
        assertEquals(201, reserved.status(), reserved.body());
        assertEquals("active", reserved.json().get("status"));
        assertEquals(3, reserved.json().get("quantity"));
        assertEquals(2, apiQuantity(productId));
        assertEquals(0, db.queryForObject("select count(*) from inventory.key where order_id = ? or reservation_id = ?::uuid", Integer.class, orderId,
                reserved.json().get("reservationId")), "ключей у резерва по API нет");
        assertEquals("active", status(orderId));
        assertEquals(List.of(5, 2), available(productId));

        Response info = stock(seller, productId);
        assertEquals(2, info.json().get("available"));
        assertEquals(3, info.json().get("reserved"));
        assertEquals(0, info.json().get("issued"));

        int version = apiVersion(productId);
        UUID other = UUID.randomUUID();
        Response refused = reserve(other, productId, 3);
        assertEquals(409, refused.status(), refused.body());
        assertEquals("insufficient-stock", code(refused));
        assertEquals(2, refused.json().get("available"));
        assertNull(reservation(other), "отказ резерв не создаёт (INV-05)");
        assertEquals(2, apiQuantity(productId));
        assertEquals(version, apiVersion(productId), "и версию не двигает");
        assertEquals(List.of(5, 2), available(productId));

        UUID exact = UUID.randomUUID();
        assertEquals(201, reserve(exact, productId, 2).status());
        assertEquals(0, apiQuantity(productId));
        assertEquals(409, reserve(UUID.randomUUID(), productId, 1).status(), "ноль единиц: заказать нечего");
        assertEquals(0, apiQuantity(productId));
    }

    @Tag("TC-416")
    @Test
    void anApiProductWhoseSellerHasNotSetTheStockCannotBeOrdered() throws Exception {
        Seller seller = new Seller();
        UUID productId = seller.product("published", "seller_api");
        UUID orderId = UUID.randomUUID();
        Response refused = reserve(orderId, productId, 1);
        assertEquals(409, refused.status(), refused.body());
        assertEquals("insufficient-stock", code(refused));
        assertEquals(0, refused.json().get("available"));
        assertNull(reservation(orderId));
    }

    @Tag("TC-416")
    @Test
    void stockVersionsOfAnApiProductGrowByOneWithoutGapsAcrossEditsAndReservations() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 4);
        UUID first = UUID.randomUUID();
        assertEquals(201, reserve(first, productId, 2).status());
        assertEquals(200, put(seller, productId, "\"3\"", quantity(9)).status());
        assertEquals(201, reserve(UUID.randomUUID(), productId, 4).status());
        UUID cancel = publishCancelled(first, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(cancel)));
        assertEquals(409, reserve(UUID.randomUUID(), productId, 9).status(), "отказ версию не двигает");

        List<Map<String, Object>> events = stockEvents(productId);
        assertEquals(List.of(4, 2, 9, 5, 7), events.stream().map(e -> e.get("available")).toList());
        long start = ((Number) events.get(0).get("version")).longValue();
        for (int i = 0; i < events.size(); i++) {
            assertEquals(start + i, ((Number) events.get(i).get("version")).longValue(), "версия события " + i + " растёт на единицу");
        }
        assertEquals(start + events.size() - 1, stockVersion(productId), "версия копии равна версии последнего события");
    }

    // ---------------------------------------------------------------- подтверждение (TC-417)

    @Tag("TC-417")
    @Test
    void confirmationUsesTheReservationWithoutKeysAndLeavesTheQuantityAndTheVersionAlone() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 5);
        UUID orderId = UUID.randomUUID();
        Response reserved = reserve(orderId, productId, 2);
        assertEquals(201, reserved.status(), reserved.body());
        int version = apiVersion(productId);

        Response confirmed = confirm(orderId, productId, 2);
        assertEquals(200, confirmed.status(), confirmed.body());
        assertEquals("used", confirmed.json().get("status"));
        assertEquals(false, confirmed.json().get("reReserved"));
        assertEquals(reserved.json().get("reservationId"), confirmed.json().get("reservationId"));
        Map<String, Object> row = reservation(orderId);
        assertEquals("used", row.get("status"));
        assertNotNull(row.get("used_at"));
        assertEquals(3, apiQuantity(productId));
        assertEquals(version, apiVersion(productId));
        assertEquals(0, db.queryForObject("select count(*) from inventory.key where order_id = ?", Integer.class, orderId), "ключ придёт от продавца");
        assertEquals(List.of(5, 3), available(productId), "количество не менялось, события остатка нет");

        Response again = confirm(orderId, productId, 2);
        assertEquals(200, again.status(), again.body());
        assertEquals(reserved.json().get("reservationId"), again.json().get("reservationId"), "повтор возвращает тот же резерв");
        assertEquals(3, apiQuantity(productId));

        Response info = stock(seller, productId);
        assertEquals(3, info.json().get("available"));
        assertEquals(0, info.json().get("reserved"));
        assertEquals(2, info.json().get("issued"), "оплаченные единицы");
    }

    @Tag("TC-417")
    @Test
    void aLatePaymentTakesTheQuantityAgainWhenItIsStillThereAndIsRefusedWhenItIsNot() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 5);
        UUID late = UUID.randomUUID();
        assertEquals(201, reserve(late, productId, 2).status());
        assertTrue(await(LIMIT, () -> "released".equals(status(late))), "срок вышел, количество вернулось");
        assertEquals(5, apiQuantity(productId));

        Response confirmed = confirm(late, productId, 2);
        assertEquals(200, confirmed.status(), confirmed.body());
        assertEquals(true, confirmed.json().get("reReserved"), "поздняя оплата: резерв создан заново (SEQ-02)");
        assertEquals("used", confirmed.json().get("status"));
        assertEquals(3, apiQuantity(productId));
        assertEquals(2, reservationsOf(late));

        UUID gone = UUID.randomUUID();
        UUID taker = UUID.randomUUID();
        assertEquals(201, reserve(gone, productId, 3).status());
        assertTrue(await(LIMIT, () -> "released".equals(status(gone))));
        assertEquals(201, reserve(taker, productId, 3).status());
        assertEquals(0, apiQuantity(productId));
        Response refused = confirm(gone, productId, 3);
        assertEquals(409, refused.status(), refused.body());
        assertEquals("insufficient-stock", code(refused));
        assertEquals(0, refused.json().get("available"));
        assertEquals("released", status(gone));
        assertEquals(1, reservationsOf(gone), "отказ нового резерва не создаёт");
    }

    // ---------------------------------------------------------------- снятие (TC-418)

    @Tag("TC-418")
    @Test
    void theTimerGivesTheQuantityBackOnceAndTheReconciliationDoesNotAddItAgain() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 5);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, productId, 3).status());
        assertEquals(2, apiQuantity(productId));

        assertTrue(await(LIMIT, () -> "released".equals(status(orderId))), "таймер снял резерв после срока");
        assertEquals("expired", reservation(orderId).get("release_reason"));
        assertEquals(5, apiQuantity(productId));
        assertTrue(await(LIMIT, () -> !events(orderId, "reservation.expired").isEmpty()));
        Thread.sleep(5_000);
        assertEquals(5, apiQuantity(productId), "сверка и повторные срабатывания количество не прибавляют");
        assertEquals(1, events(orderId, "reservation.expired").size());
        assertEquals(List.of(5, 2, 5), available(productId));
    }

    @Tag("TC-418")
    @Test
    void cancellationBeforePaymentGivesTheQuantityBackOnceWhateverTheNumberOfEvents() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 5);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, productId, 4).status());

        UUID first = publishCancelled(orderId, "payment_declined");
        assertTrue(await(LIMIT, () -> processed(first)));
        assertEquals("released", status(orderId));
        assertEquals("payment_declined", reservation(orderId).get("release_reason"));
        assertEquals(5, apiQuantity(productId));

        int events = stockEvents(productId).size();
        send(first, orderId, Map.of("orderId", orderId.toString(), "cancelReason", "payment_declined"));
        UUID again = publishCancelled(orderId, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(again)));
        assertEquals(5, apiQuantity(productId), "дубль и новое событие по снятому резерву количество не прибавляют");
        assertEquals("payment_declined", reservation(orderId).get("release_reason"));
        assertEquals(events, stockEvents(productId).size());
        assertEquals(1, events(orderId, "reservation.released").size());
    }

    @Tag("TC-418")
    @Test
    void cancellationAfterPaymentReleasesTheUsedReservationOfAnApiProductOnce() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 5);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, productId, 3).status());
        assertEquals(200, confirm(orderId, productId, 3).status());
        assertEquals(2, apiQuantity(productId));

        UUID declined = publishCancelled(orderId, "payment_declined");
        assertTrue(await(LIMIT, () -> processed(declined)));
        assertEquals("used", status(orderId), "отказ платежа к оплаченному заказу не относится");
        assertEquals(2, apiQuantity(productId));

        UUID cancel = publishCancelled(orderId, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(cancel)));
        assertEquals("released", status(orderId), "SM-08/T5: использован > снят");
        Map<String, Object> row = reservation(orderId);
        assertEquals("order_cancelled", row.get("release_reason"));
        assertNotNull(row.get("used_at"), "время использования остаётся: оно показывает, что оплата была");
        assertEquals(5, apiQuantity(productId), "количество вернулось");
        List<Map<String, Object>> released = events(orderId, "reservation.released");
        assertEquals(1, released.size());
        assertEquals("order_cancelled", released.get(0).get("reason"));
        assertEquals(List.of(5, 2, 5), available(productId));

        UUID repeat = publishCancelled(orderId, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(repeat)));
        assertEquals(5, apiQuantity(productId), "повтор события количество не прибавляет");
        assertEquals(1, events(orderId, "reservation.released").size());
    }

    @Tag("TC-418")
    @Test
    void aUsedReservationOfAKeyPoolProductCannotBeReleasedNotEvenByTheDatabase() throws Exception {
        Pool pool = pool(2);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 1).status());
        assertEquals(200, confirm(orderId, pool.productId(), 1).status());
        assertEquals(1, keysOfOrder(orderId, "issued"));

        UUID cancel = publishCancelled(orderId, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(cancel)));
        assertEquals("used", status(orderId), "INV-04: выданный ключ в оборот не возвращается");
        assertEquals(1, keysOfOrder(orderId, "issued"));

        DataIntegrityViolationException refused = assertThrows(DataIntegrityViolationException.class, () -> db.update(
                "update inventory.reservation set status = 'released', release_reason = 'order_cancelled', released_at = now() where order_id = ?", orderId));
        assertTrue(String.valueOf(refused.getMessage()).contains("только у товара с выдачей по API"), "триггер T5: " + refused.getMessage());
        assertEquals("used", status(orderId));
    }
}
