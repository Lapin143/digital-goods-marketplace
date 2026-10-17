package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Пул ключей на стенде (шаг 14 фазы 4, US-4.2, US-4.3, US-4.5, ADR-009, INV-03, INV-20): реестр товаров по событиям каталога, загрузка
 * списком и файлом, отчёт без значений, шифрование в базе, дубли в пуле и при параллельной загрузке, предел пула, остаток и событие
 * {@code stock.changed} в той же транзакции, права по трём операциям (ST-02). Сервис работает в процессе теста, база и Kafka это набор
 * {@code dev-kit} (общая основа {@link InventoryStandIT}).
 */
class KeyPoolIT extends InventoryStandIT {

    private static final String PRODUCTS = "/api/v1/seller/products";

    private static StandHttp gateway;
    private static JdbcTemplate db;
    private static KafkaProducer<String, String> catalog;

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        db = new JdbcTemplate(Stand.dataSource("migrator_inventory", "inventory_db"));
        catalog = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("catalog-service"), "catalog-service-it");
    }

    @AfterAll
    static void close() {
        catalog.close(Duration.ofSeconds(5));
    }

    // ---------------------------------------------------------------- помощники

    /** Продавец с токеном кабинета: роль seller, область seller.catalog, второй фактор и claim seller_id. */
    private static final class Seller {
        final UUID sellerId = UUID.randomUUID();
        final String[] auth = StandHttp.bearer(TOKENS.token().subject(UUID.randomUUID().toString()).scopes("seller.catalog").roles("seller")
                .amr("pwd", "otp").sellerId(sellerId.toString()).build());

        /** Товар продавца в реестре: копия, как её завёл бы обработчик событий каталога. */
        UUID product(String status, String issuance) {
            UUID productId = UUID.randomUUID();
            db.update("insert into inventory.product_copy (product_id, seller_id, issuance_method, status, source_version) values (?, ?, ?, ?, 1)",
                    productId, sellerId, issuance, status);
            return productId;
        }

        UUID product() {
            return product("draft", "key_pool");
        }
    }

    private static String[] with(String[] auth, String... extra) {
        String[] all = new String[auth.length + extra.length];
        System.arraycopy(auth, 0, all, 0, auth.length);
        System.arraycopy(extra, 0, all, auth.length, extra.length);
        return all;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String code(Response response) {
        return (String) response.json().get("code");
    }

    private static String batchBody(String... keys) {
        List<Object> list = new ArrayList<>(List.of((Object[]) keys));
        return Json.write(Map.of("keys", list));
    }

    private static Response batch(Seller seller, UUID productId, String... keys) throws IOException {
        return gateway.post(PRODUCTS + "/" + productId + "/key-batches", batchBody(keys), with(seller.auth, "Idempotency-Key", key()));
    }

    private static Response file(Seller seller, UUID productId, String csv) throws IOException {
        return gateway.request("POST", PRODUCTS + "/" + productId + "/key-files", "text/csv", csv.getBytes(StandardCharsets.UTF_8),
                with(seller.auth, "Idempotency-Key", key()));
    }

    private static Response stock(Seller seller, UUID productId) throws IOException {
        return gateway.get(PRODUCTS + "/" + productId + "/stock", seller.auth);
    }

    private static List<Map<String, Object>> rejected(Response response) {
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (Object item : (List<?>) response.json().get("rejected")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) item;
            rejected.add(line);
        }
        return rejected;
    }

    private static Map<String, Object> line(int number, String reason) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("line", number);
        line.put("reason", reason);
        return line;
    }

    private static List<Map<String, Object>> stockEvents(UUID productId) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = 'stock.changed' order by id",
                String.class, productId.toString())) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    private static int count(UUID productId) {
        Integer n = db.queryForObject("select count(*) from inventory.key where product_id = ?", Integer.class, productId);
        return n == null ? 0 : n;
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static boolean await(Duration limit, Condition condition) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.holds();
    }

    /** Публикует событие товара от имени каталога: права Kafka выданы по CN сертификата. Ключ записи равен {@code subject}. */
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

    private static Map<String, Object> productData(UUID productId, UUID sellerId, String status, int version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId.toString());
        data.put("sellerId", sellerId.toString());
        data.put("issuanceMethod", "key_pool");
        data.put("status", status);
        data.put("version", version);
        return data;
    }

    private static String copyStatus(UUID productId) {
        List<String> rows = db.queryForList("select status from inventory.product_copy where product_id = ?", String.class, productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- реестр товаров

    @Test
    void theRegistryFollowsCatalogEventsByVersionAndDecisions() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        publishProduct("product.created", productId, productData(productId, sellerId, "draft", 1));
        assertTrue(await(Duration.ofSeconds(30), () -> "draft".equals(copyStatus(productId))), "копия товара создана событием");

        publishProduct("product.updated", productId, Map.of("productId", productId.toString(), "sellerId", sellerId.toString(), "issuanceMethod",
                "key_pool", "status", "on_moderation", "version", 3, "changedFields", List.of("status")));
        assertTrue(await(Duration.ofSeconds(30), () -> "on_moderation".equals(copyStatus(productId))));

        // событие с меньшей версией опоздало и копию не откатывает
        publishProduct("product.updated", productId, Map.of("productId", productId.toString(), "sellerId", sellerId.toString(), "issuanceMethod",
                "key_pool", "status", "draft", "version", 2, "changedFields", List.of("status")));
        publishProduct("product.published", productId, Map.of("productId", productId.toString(), "sellerId", sellerId.toString(), "title", "Лунная тропа"));
        assertTrue(await(Duration.ofSeconds(30), () -> "published".equals(copyStatus(productId))), "решение модератора меняет статус копии");
        assertEquals(3, db.queryForObject("select source_version from inventory.product_copy where product_id = ?", Integer.class, productId));

        publishProduct("product.blocked", productId, Map.of("productId", productId.toString(), "sellerId", sellerId.toString(), "title", "Лунная тропа",
                "cause", "moderator_decision", "reason", "Жалоба"));
        assertTrue(await(Duration.ofSeconds(30), () -> "blocked".equals(copyStatus(productId))));
    }

    // ---------------------------------------------------------------- загрузка списком

    @Test
    void aBatchIsEncryptedInTheDatabaseAndTheReportCarriesNoValues() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        Response response = batch(seller, productId, "ZZQ-SECRET-0001", "ZZQ-SECRET-0002", "ZZQ-SECRET-0002", "", "ZZQ-SECRET-0003");
        assertEquals(200, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals(3, response.json().get("accepted"));
        assertEquals(2, response.json().get("rejectedTotal"));
        assertEquals(List.of(line(3, "duplicate_in_upload"), line(4, "empty")), rejected(response));
        assertEquals(3, response.json().get("available"));
        assertEquals(100000, response.json().get("poolLimit"));
        assertFalse(response.body().contains("ZZQ-SECRET"), "значений ключей в ответе нет");

        assertEquals(3, count(productId));
        Integer plain = db.queryForObject("select count(*) from inventory.key where position(convert_to('ZZQ-SECRET', 'UTF8') in ciphertext) > 0 "
                + "or position(convert_to('ZZQ-SECRET', 'UTF8') in hmac) > 0 or position(convert_to('ZZQ-SECRET', 'UTF8') in nonce) > 0", Integer.class);
        assertEquals(0, plain, "значения в базе нет ни в открытом виде, ни в HMAC (INV-20)");
        Map<String, Object> row = db.queryForMap("select dek_id, octet_length(nonce) n, octet_length(hmac) h, status, order_id, reservation_id "
                + "from inventory.key where product_id = ? limit 1", productId);
        assertEquals(12, row.get("n"));
        assertEquals(32, row.get("h"));
        assertEquals("free", row.get("status"));
        assertNull(row.get("order_id"));
        Map<String, Object> dek = db.queryForMap("select octet_length(wrapped_key) w, status from inventory.data_key where id = ?", row.get("dek_id"));
        assertEquals(60, dek.get("w"), "ключ данных в базе только в обёрнутом виде");
        assertEquals("active", dek.get("status"));
        assertEquals(1, db.queryForObject("select count(*) from inventory.data_key where status = 'active'", Integer.class));
    }

    @Test
    void stockChangedIsInTheSameTransactionAndTheStockMatches() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        batch(seller, productId, "A-1", "A-2");
        batch(seller, productId, "A-3");
        List<Map<String, Object>> events = stockEvents(productId);
        assertEquals(2, events.size());
        assertEquals(2, events.get(0).get("available"));
        assertEquals(3, events.get(1).get("available"));
        assertEquals(productId.toString(), events.get(1).get("productId"));
        assertTrue(((Number) events.get(1).get("version")).longValue() >= ((Number) events.get(0).get("version")).longValue());

        Response stock = stock(seller, productId);
        assertEquals(200, stock.status(), stock.body());
        assertEquals("no-store", stock.header("Cache-Control"));
        assertEquals(3, stock.json().get("available"));
        assertEquals(0, stock.json().get("reserved"));
        assertEquals(0, stock.json().get("issued"));
        assertEquals(3, stock.json().get("poolSize"));
        assertEquals(100000, stock.json().get("poolLimit"));
        assertNotNull(stock.json().get("updatedAt"));
    }

    @Test
    void anUploadThatAcceptsNothingWritesNoEvent() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        batch(seller, productId, "A-1");
        int before = stockEvents(productId).size();
        Response again = batch(seller, productId, "A-1", "  A-1  ", "");
        assertEquals(200, again.status());
        assertEquals(0, again.json().get("accepted"));
        assertEquals(List.of(line(1, "duplicate_in_pool"), line(2, "duplicate_in_upload"), line(3, "empty")), rejected(again));
        assertEquals(before, stockEvents(productId).size());
    }

    @Test
    void duplicatesInThePoolAreRejectedWithLineNumbersAndOtherProductsAreIndependent() throws IOException {
        Seller seller = new Seller();
        UUID first = seller.product();
        UUID second = seller.product("published", "key_pool");
        batch(seller, first, "A-1", "A-2");
        Response again = batch(seller, first, "A-2", "A-4", "A-1", "A-5");
        assertEquals(2, again.json().get("accepted"));
        assertEquals(List.of(line(1, "duplicate_in_pool"), line(3, "duplicate_in_pool")), rejected(again));
        assertEquals(4, again.json().get("available"));
        assertEquals(2, batch(seller, second, "A-1", "A-2").json().get("accepted"), "то же значение в другом товаре не дубль");
        assertEquals(4, count(first));
    }

    @Test
    void theSameValueUploadedByTwoSessionsAtOnceIsAcceptedExactlyOnce() throws Exception {
        Seller seller = new Seller();
        UUID productId = seller.product();
        int sessions = 6;
        ExecutorService pool = Executors.newFixedThreadPool(sessions);
        try {
            List<Future<Response>> calls = new ArrayList<>();
            for (int i = 0; i < sessions; i++) {
                Callable<Response> call = () -> batch(seller, productId, "RACE-0001");
                calls.add(pool.submit(call));
            }
            int accepted = 0;
            int duplicates = 0;
            for (Future<Response> call : calls) {
                Response response = call.get(60, TimeUnit.SECONDS);
                assertEquals(200, response.status(), response.body());
                accepted += (Integer) response.json().get("accepted");
                duplicates += rejected(response).stream().filter(r -> "duplicate_in_pool".equals(r.get("reason"))).count();
            }
            assertEquals(1, accepted, "ровно одна сессия приняла значение");
            assertEquals(sessions - 1, duplicates);
            assertEquals(1, count(productId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameIdempotencyKeyRepeatsTheAnswerAndTheKeyIsRequired() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        String idempotencyKey = key();
        String path = PRODUCTS + "/" + productId + "/key-batches";
        Response first = gateway.post(path, batchBody("A-1", "A-2"), with(seller.auth, "Idempotency-Key", idempotencyKey));
        Response second = gateway.post(path, batchBody("A-1", "A-2"), with(seller.auth, "Idempotency-Key", idempotencyKey));
        assertEquals(200, first.status(), first.body());
        assertEquals(first.json(), second.json());
        assertEquals("true", second.header("Idempotency-Replayed"));
        assertEquals(2, count(productId));
        assertEquals(1, stockEvents(productId).size());

        Response missing = gateway.post(path, batchBody("A-3"), seller.auth);
        assertEquals(400, missing.status());
        assertEquals("idempotency-key-required", code(missing));
        Response reused = gateway.post(path, batchBody("OTHER-1"), with(seller.auth, "Idempotency-Key", idempotencyKey));
        assertEquals(422, reused.status());
        assertEquals("idempotency-key-reuse", code(reused));
    }

    @Test
    void invalidBodiesAreRefusedWithoutStoringAnything() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        String path = PRODUCTS + "/" + productId + "/key-batches";
        Response empty = gateway.post(path, "{\"keys\":[]}", with(seller.auth, "Idempotency-Key", key()));
        assertEquals(422, empty.status());
        assertEquals("validation-failed", code(empty));
        assertEquals(422, gateway.post(path, "{}", with(seller.auth, "Idempotency-Key", key())).status());
        assertEquals(422, gateway.post(path, "{\"keys\":[1]}", with(seller.auth, "Idempotency-Key", key())).status());
        assertEquals(400, gateway.post(path, "не JSON", with(seller.auth, "Idempotency-Key", key())).status());
        Response csv = file(seller, productId, "A-1\n");
        assertEquals(200, csv.status());
        Response wrongType = gateway.request("POST", path, "text/csv", "A-9".getBytes(StandardCharsets.UTF_8), with(seller.auth, "Idempotency-Key", key()));
        assertEquals(415, wrongType.status());
        assertEquals(1, count(productId));
        assertEquals(400, gateway.post(PRODUCTS + "/not-a-uuid/key-batches", batchBody("A"), with(seller.auth, "Idempotency-Key", key())).status());
    }

    // ---------------------------------------------------------------- загрузка файлом

    @Test
    void aCsvFileIsReadLineByLineAndTheReportNamesTheRejectedLines() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        String csv = "CSV-1\r\nCSV-2\r\n\r\nCSV-1\r\n" + "x".repeat(1025) + "\r\nCSV-3\r\n";
        Response response = file(seller, productId, csv);
        assertEquals(200, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals(3, response.json().get("accepted"));
        assertEquals(List.of(line(3, "empty"), line(4, "duplicate_in_upload"), line(5, "too_long")), rejected(response));
        assertEquals(3, count(productId));
        assertFalse(response.body().contains("CSV-"));

        Response again = file(seller, productId, "CSV-1\nCSV-2\nCSV-9");
        assertEquals(1, again.json().get("accepted"));
        assertEquals(List.of(line(1, "duplicate_in_pool"), line(2, "duplicate_in_pool")), rejected(again));
    }

    @Test
    void aFileOverTheLimitsIsRefusedAndNothingIsSaved() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 10_001; i++) {
            many.append("L").append(i).append('\n');
        }
        Response tooMany = file(seller, productId, many.toString());
        assertEquals(413, tooMany.status());
        assertEquals("payload-too-large", code(tooMany));
        Response empty = file(seller, productId, "");
        assertEquals(422, empty.status());
        Response latin1 = gateway.request("POST", PRODUCTS + "/" + productId + "/key-files", "text/csv", new byte[] {'A', (byte) 0xE9, '\n'},
                with(seller.auth, "Idempotency-Key", key()));
        assertEquals(422, latin1.status());
        assertEquals(0, count(productId));
    }

    // ---------------------------------------------------------------- пределы и состояния товара

    @Test
    void thePoolIsLimitedToOneHundredThousandAndAnOverflowingUploadIsRefusedWhole() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        assertEquals(1, batch(seller, productId, "BASE-1").json().get("accepted"), "первая загрузка заводит ключ данных");
        Integer dekId = db.queryForObject("select dek_id from inventory.key where product_id = ?", Integer.class, productId);
        db.update("insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) "
                + "select gen_random_uuid(), ?, ?, decode('000000000000000000000000', 'hex'), decode(repeat('00', 17), 'hex'), "
                + "decode(md5(i::text) || md5(i::text), 'hex') from generate_series(1, 99994) i", productId, dekId);
        assertEquals(99_995, count(productId));

        Response over = batch(seller, productId, "N-1", "N-2", "N-3", "N-4", "N-5", "N-6");
        assertEquals(409, over.status(), over.body());
        assertEquals("pool-limit-exceeded", code(over));
        assertEquals(99_995, count(productId), "загрузка отклонена целиком");
        assertEquals(5, batch(seller, productId, "N-1", "N-2", "N-3", "N-4", "N-5").json().get("accepted"), "ровно до предела");
        assertEquals(100_000, count(productId));
        assertEquals(409, batch(seller, productId, "N-6").status());
    }

    @Test
    void onlyKeyPoolProductsInAnAllowedStatusAcceptKeys() throws IOException {
        Seller seller = new Seller();
        for (String status : List.of("draft", "on_moderation", "published", "rejected")) {
            assertEquals(200, batch(seller, seller.product(status, "key_pool"), "A-" + status).status(), status);
        }
        for (String status : List.of("blocked", "archived")) {
            UUID productId = seller.product(status, "key_pool");
            Response refused = batch(seller, productId, "A-1");
            assertEquals(409, refused.status(), status);
            assertEquals("state-conflict", code(refused));
            assertEquals(0, count(productId));
        }
        Response api = batch(seller, seller.product("published", "seller_api"), "A-1");
        assertEquals(409, api.status());
        assertEquals("state-conflict", code(api));
    }

    @Test
    void anotherSellersProductAndAnUnknownProductAreNotFoundForEveryOperation() throws IOException {
        Seller owner = new Seller();
        Seller stranger = new Seller();
        UUID productId = owner.product();
        for (UUID target : List.of(productId, UUID.randomUUID())) {
            Response upload = batch(stranger, target, "A-1");
            assertEquals(404, upload.status());
            assertEquals("not-found", code(upload));
            assertEquals(404, file(stranger, target, "A-1\n").status());
            assertEquals(404, stock(stranger, target).status());
        }
        assertEquals(0, count(productId));
    }

    /**
     * Права рабочей роли на {@code key}: читает, вставляет, обновляет, но не удаляет и не очищает таблицу (единица товара не исчезает,
     * аннулирование это статус). Проверка по каталогу прав: у роли {@code app_inventory} предел 8 соединений, и все они заняты сервисом.
     */
    @Test
    void theApplicationRoleHasNoDeleteOrTruncateOnKeys() {
        for (String right : List.of("SELECT", "INSERT", "UPDATE")) {
            assertTrue(Boolean.TRUE.equals(db.queryForObject("select has_table_privilege('app_inventory'::name, 'inventory.key'::text, ?::text)", Boolean.class, right)),
                    right);
        }
        for (String right : List.of("DELETE", "TRUNCATE")) {
            assertFalse(Boolean.TRUE.equals(db.queryForObject("select has_table_privilege('app_inventory'::name, 'inventory.key'::text, ?::text)", Boolean.class, right)),
                    right);
        }
    }

    // ---------------------------------------------------------------- права доступа (ST-02)

    private record Operation(String name, String method, String path) {
    }

    private static final String SOME = "0199e0a0-0000-4000-8000-0000000000dd";
    private static final List<Operation> OPERATIONS = List.of(
            new Operation("uploadKeyBatch", "POST", PRODUCTS + "/" + SOME + "/key-batches"),
            new Operation("uploadKeyFile", "POST", PRODUCTS + "/" + SOME + "/key-files"),
            new Operation("getProductStock", "GET", PRODUCTS + "/" + SOME + "/stock"));

    private static Response call(Operation operation, String[] auth) throws IOException {
        if (operation.method.equals("GET")) {
            return gateway.get(operation.path, auth);
        }
        if (operation.name.equals("uploadKeyFile")) {
            return gateway.request("POST", operation.path, "text/csv", "A-1\n".getBytes(StandardCharsets.UTF_8), with(auth, "Idempotency-Key", key()));
        }
        return gateway.post(operation.path, batchBody("A-1"), with(auth, "Idempotency-Key", key()));
    }

    @Test
    void everyOperationRefusesCallersWithoutTheRight() throws IOException {
        String sellerId = UUID.randomUUID().toString();
        String noScope = TOKENS.token().subject(sellerId).scopes("orders.read").roles("seller").amr("pwd", "otp").sellerId(sellerId).build();
        String buyer = TOKENS.token().scopes("seller.catalog").roles("buyer").amr("pwd").build();
        String moderator = TOKENS.token().scopes("seller.catalog").roles("moderator").amr("pwd", "otp").build();
        String noSecondFactor = TOKENS.token().subject(sellerId).scopes("seller.catalog").roles("seller").amr("pwd").sellerId(sellerId).build();
        String smsOnly = TOKENS.token().subject(sellerId).scopes("seller.catalog").roles("seller").amr("sms").sellerId(sellerId).build();
        for (Operation operation : OPERATIONS) {
            Response anonymous = call(operation, new String[0]);
            assertEquals(401, anonymous.status(), operation.name + ": без токена");
            assertEquals("unauthenticated", code(anonymous), operation.name);
            assertEquals(403, call(operation, StandHttp.bearer(noScope)).status(), operation.name + ": чужая область");
            for (String wrongRole : List.of(buyer, moderator)) {
                Response response = call(operation, StandHttp.bearer(wrongRole));
                assertEquals(403, response.status(), operation.name + ": чужая роль");
                assertEquals("forbidden", code(response), operation.name);
            }
            Response noOtp = call(operation, StandHttp.bearer(noSecondFactor));
            assertEquals(403, noOtp.status(), operation.name + ": без второго фактора");
            assertEquals("second-factor-required", code(noOtp), operation.name);
            assertEquals(403, call(operation, StandHttp.bearer(smsOnly)).status(), operation.name + ": сессия по SMS");
        }
    }

    @Test
    void aSellerTokenWithoutTheSellerIdClaimOwnsNothing() throws IOException {
        String token = TOKENS.token().subject(UUID.randomUUID().toString()).scopes("seller.catalog").roles("seller").amr("pwd", "otp").build();
        Response response = gateway.get(PRODUCTS + "/" + SOME + "/stock", StandHttp.bearer(token));
        assertEquals(403, response.status());
        assertEquals("forbidden", code(response));
    }
}
