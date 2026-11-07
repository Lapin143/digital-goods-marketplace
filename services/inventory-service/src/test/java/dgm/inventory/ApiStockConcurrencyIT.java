package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.service.ReservationService;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.Concurrent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Остаток товара с выдачей по API при конкуренции (шаг 10 среза 1 Ф5, К14; ADR-008, SM-08, INV-05, INV-09, US-4.7, NFT-2.2). Две
 * гонки разной природы. «Система против системы»: сто заказов одновременно берут по единице из остатка 60, затем сто одновременных
 * снятий возвращают его ровно по числу активных резервов. «Человек против системы»: продавец правит остаток по версии, которую
 * прочитал до того, как пошли заказы. Условное {@code UPDATE} резерва ({@code quantity >= :n}) и правка с {@code version = :v}
 * проверяются здесь, а не только ограничением {@code CHECK} базы: мутант без условия должен делать красным тест, а не ограничение.
 *
 * <p>Потоки стартуют по общей защёлке ({@link Concurrent#race}); доказательство это запросы к базе и события {@code stock.changed}, а
 * не только ответы потоков. Резервы и снятия идут по HTTP ({@code reserve}) и напрямую через {@link ReservationService#release}:
 * потребитель {@code order.cancelled} в сервисе один, гонку двух обработок вызывает только прямой вызов ядра (так же в тесте
 * границы срока и оплаты для {@code expire}). Срок резерва в тестах 6 секунд, а таймер и сверка просыпаются часто (свойства {@link InventoryStandIT}): итоговые
 * проверки поэтому не зависят от того, кто снял резерв, отмена или срок, а считают остаток и события в целом. Задание CI
 * {@code concurrency} повторяет тесты с тегом {@code concurrency} 50 раз подряд (tools/ci/concurrency_series.sh).
 */
@Tag("concurrency")
class ApiStockConcurrencyIT extends ReservationStandIT {

    private static final int STOCK = 60;
    private static final int BUYERS = 100;

    @Autowired
    private ReservationService reservations;

    // ---------------------------------------------------------------- помощники

    private static Response put(Seller seller, UUID productId, String ifMatch, int quantity) throws IOException {
        return gateway.request("PUT", PRODUCTS + "/" + productId + "/api-stock", "application/json", ("{\"quantity\":" + quantity + "}").getBytes(StandardCharsets.UTF_8),
                with(seller.auth, "If-Match", ifMatch));
    }

    /** Товар по API с заданным остатком: правка продавцом по версии, как в кабинете. Версия строки после этого 2. */
    private static UUID productWith(Seller seller, int quantity) throws IOException {
        UUID productId = seller.product("published", "seller_api");
        Response set = put(seller, productId, "\"1\"", quantity);
        assertEquals(200, set.status(), set.body());
        return productId;
    }

    private static int apiQuantity(UUID productId) {
        return db.queryForObject("select quantity from inventory.api_stock where product_id = ?", Integer.class, productId);
    }

    private static int apiVersion(UUID productId) {
        return db.queryForObject("select version from inventory.api_stock where product_id = ?", Integer.class, productId);
    }

    private static List<UUID> newOrders(int count) {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(UUID.randomUUID());
        }
        return orders;
    }

    /** Сколько событий {@code stock.changed} товара несут именно это значение остатка. */
    private static long eventsWithAvailable(UUID productId, int available) {
        return stockEvents(productId).stream().filter(e -> Integer.valueOf(available).equals(e.get("available"))).count();
    }

    // ---------------------------------------------------------------- TC-037: сто заказов против остатка 60

    @Tag("TC-037")
    @Test
    void hundredBuyersOfOneUnitEachForSixtyUnitsGetExactlySixtyReservationsAndTheStockStaysExact() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, STOCK);
        int versionBefore = apiVersion(productId);
        List<UUID> orders = newOrders(BUYERS);

        List<Response> responses = Concurrent.race(BUYERS, buyer -> reserve(orders.get(buyer), productId, 1));

        int created = 0;
        int refused = 0;
        for (Response response : responses) {
            if (response.status() == 201) {
                created++;
            } else {
                refused++;
                assertEquals(409, response.status(), response.body());
                assertEquals("insufficient-stock", code(response));
                assertEquals(0, response.json().get("available"), "отказ называет остаток: нуль, ведь заказывали по единице");
            }
        }
        assertEquals(STOCK, created, "ровно шестьдесят заказов получили резерв");
        assertEquals(BUYERS - STOCK, refused, "остальные сорок получили отказ «не хватает»");

        assertEquals(0, apiQuantity(productId), "остаток нуль и не отрицателен (INV-09)");
        assertEquals(versionBefore + STOCK, apiVersion(productId), "версия выросла ровно на число резервов, отказы её не двигают");
        assertEquals(STOCK, db.queryForObject("select count(*) from inventory.reservation where product_id = ? and status = 'active' and quantity = 1",
                Integer.class, productId));
        assertEquals(STOCK, db.queryForObject("select count(*) from inventory.reservation where product_id = ?", Integer.class, productId),
                "отказ резерв не создаёт (INV-05)");
        assertEquals(0, db.queryForObject("select count(*) from (select order_id from inventory.reservation where product_id = ? group by order_id "
                + "having count(*) > 1) twice", Integer.class, productId), "ни один заказ не держит два резерва");

        // События: правка продавца и шестьдесят резервов, каждое значение остатка от 60 до 0 ровно один раз, версии подряд без пропусков
        List<Map<String, Object>> events = stockEvents(productId);
        assertEquals(1 + STOCK, events.size(), "отказы события остатка не пишут");
        for (int available = 0; available <= STOCK; available++) {
            assertEquals(1, eventsWithAvailable(productId, available), "остаток " + available + " объявлен один раз");
        }
        List<Long> versions = events.stream().map(e -> ((Number) e.get("version")).longValue()).sorted().toList();
        for (int i = 1; i < versions.size(); i++) {
            assertEquals(versions.get(i - 1) + 1, versions.get(i), "версия события растёт на единицу без пропусков");
        }
    }

    // ---------------------------------------------------------------- TC-037: сто одновременных снятий

    @Tag("TC-037")
    @Test
    void hundredParallelReleasesGiveBackExactlyTheActiveReservationsAndARepeatAddsNothing() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, STOCK);
        int versionBefore = apiVersion(productId);
        List<UUID> orders = newOrders(STOCK);
        List<Response> reserved = Concurrent.race(STOCK, buyer -> reserve(orders.get(buyer), productId, 1));
        for (Response response : reserved) {
            assertEquals(201, response.status(), response.body());
        }
        assertEquals(versionBefore + STOCK, apiVersion(productId));

        // Сто участников снимают резервы шестидесяти заказов: заказы 0 – 39 встречаются дважды (повтор отмены), 40 – 59 один раз
        List<Optional<Reservation>> results = Concurrent.race(BUYERS, index -> reservations.release(orders.get(index % STOCK), Reservation.ORDER_CANCELLED));

        long released = results.stream().filter(Optional::isPresent).count();
        long byCancel = db.queryForObject("select count(*) from inventory.reservation where product_id = ? and status = 'released' and release_reason = 'order_cancelled'",
                Long.class, productId);
        assertEquals(byCancel, released, "снятий по отмене столько, сколько вызовов получили резерв: повтор не снимает второй раз");
        assertEquals(STOCK, db.queryForObject("select count(*) from inventory.reservation where product_id = ? and status = 'released'", Integer.class, productId),
                "все шестьдесят резервов сняты: отменой или сроком, но один раз");
        assertEquals(STOCK, apiQuantity(productId), "остаток вернулся ровно на число резервов, не больше (повтор события не прибавляет)");
        assertEquals(versionBefore + 2 * STOCK, apiVersion(productId), "шестьдесят уменьшений и шестьдесят увеличений, повторы версию не двигали");
        assertEquals(0, db.queryForObject("select count(*) from inventory.reservation where product_id = ? and status = 'active'", Integer.class, productId));

        int releasedEvents = 0;
        for (UUID orderId : orders) {
            int n = events(orderId, "reservation.released").size();
            assertTrue(n <= 1, "событие снятия не больше одного на заказ");
            releasedEvents += n;
        }
        assertEquals(byCancel, releasedEvents, "событие снятия есть ровно у резервов, снятых отменой");

        // Остаток 60 → 0 → 60: каждое промежуточное значение объявлено дважды, 0 один раз, 60 дважды (правка продавца и последнее снятие)
        assertEquals(1 + 2 * STOCK, stockEvents(productId).size(), "правка, шестьдесят резервов и шестьдесят возвратов");
        assertEquals(1, eventsWithAvailable(productId, 0));
        for (int available = 1; available <= STOCK; available++) {
            assertEquals(2, eventsWithAvailable(productId, available), "остаток " + available + " объявлен на спуске и на подъёме");
        }
    }

    // ---------------------------------------------------------------- TC-037: продавец правит, пока идёт заказ

    @Tag("TC-037")
    @Test
    void aSellerEditByAStaleVersionIsRefusedWithTheCurrentStockAndTheHeldUnitsDoNotComeBack() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 10);
        String readVersion = "\"" + apiVersion(productId) + "\"";
        List<UUID> orders = newOrders(3);
        for (UUID orderId : orders) {
            assertEquals(201, reserve(orderId, productId, 1).status());
        }
        assertEquals(7, apiQuantity(productId));
        int current = apiVersion(productId);

        Response stale = put(seller, productId, readVersion, 12);
        assertEquals(412, stale.status(), stale.body());
        assertEquals("version-conflict", code(stale));
        assertEquals(7, stale.json().get("available"), "тело называет текущий остаток");
        assertEquals("\"" + current + "\"", stale.header("ETag"), "и текущую версию, с которой повторять");
        assertEquals(7, apiQuantity(productId), "количество не изменилось");
        assertEquals(current, apiVersion(productId));

        Response applied = put(seller, productId, "\"" + current + "\"", 12);
        assertEquals(200, applied.status(), applied.body());
        assertEquals("\"" + (current + 1) + "\"", applied.header("ETag"));
        assertEquals(12, apiQuantity(productId));
        Response info = stock(seller, productId);
        assertEquals(12, info.json().get("available"), "продавец задал свободный остаток");
        assertEquals(3, info.json().get("reserved"), "три проданные единицы не воскресли и из остатка не вычтены второй раз");
    }

    // ---------------------------------------------------------------- TC-037: правка и заказы одновременно

    @Tag("TC-037")
    @Test
    void aSellerEditRacingTenOrdersEndsInExactlyOneOfTwoConsistentStates() throws Exception {
        Seller seller = new Seller();
        UUID productId = productWith(seller, 20);
        String readVersion = "\"" + apiVersion(productId) + "\"";
        int versionBefore = apiVersion(productId);
        int orders = 10;
        List<UUID> orderIds = newOrders(orders);

        List<Response> responses = Concurrent.race(orders + 1,
                index -> index < orders ? reserve(orderIds.get(index), productId, 1) : put(seller, productId, readVersion, 50));

        for (int i = 0; i < orders; i++) {
            assertEquals(201, responses.get(i).status(), "остатка хватает при любом порядке: " + responses.get(i).body());
        }
        Response edit = responses.get(orders);
        if (edit.status() == 200) {
            assertEquals(50 - orders, apiQuantity(productId), "правка успела первой: пятьдесят минус десять заказов");
            assertEquals(versionBefore + 1 + orders, apiVersion(productId));
        } else {
            assertEquals(412, edit.status(), edit.body());
            assertEquals("version-conflict", code(edit));
            assertEquals(20 - orders, apiQuantity(productId), "хотя бы один заказ успел раньше: правка отвергнута, потерянного обновления нет");
            assertEquals(versionBefore + orders, apiVersion(productId));
        }
        assertEquals(orders, db.queryForObject("select count(*) from inventory.reservation where product_id = ? and status in ('active', 'released')", Integer.class,
                productId));
    }
}
