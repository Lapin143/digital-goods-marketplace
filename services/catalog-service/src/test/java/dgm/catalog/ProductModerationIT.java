package dgm.catalog;

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
import dgm.kit.redis.KitRedis;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Модерация товаров, карточка витрины и остаток на стенде (шаг 13 фазы 4, SM-04, переходы T3, T4, T8, T11, US-3.3 – US-3.5, US-3.9,
 * US-4.5, FT-4.4, NFT-1.0): очередь и карточка модератора, три решения с событием и записью аудита одной транзакцией, откат решения,
 * карточка на витрине с кэшем в Redis и ETag, сброс кэша по решению и по {@code stock.changed}, остаток из события в нарушенном порядке,
 * права по шести операциям (ST-02). Сервис работает в процессе теста, база, Kafka и Redis это набор {@code dev-kit}
 * (общая основа {@link CatalogStandIT}).
 */
class ProductModerationIT extends CatalogStandIT {

    private static final String SELLER_PRODUCTS = "/api/v1/seller/products";
    private static final String STAFF = "/api/v1/staff/products";
    private static final String STOREFRONT = "/api/v1/products";
    private static final UUID MODERATOR = UUID.fromString("0199e0a0-0000-7000-8000-0000000000b2");
    private static final String MODERATOR_ADDRESS = "203.0.113.9";
    private static final String TITLE = "Лунная тропа: ключ Steam";
    private static final String INPUT = "{\"title\":\"" + TITLE + "\",\"description\":\"Приключенческая игра, ключ активации Steam\","
            + "\"productType\":\"game_key\",\"platform\":\"Steam\",\"activationRegions\":[\"RU\",\"KZ\"],"
            + "\"price\":{\"amount\":149900,\"currency\":\"RUB\"},\"issuanceMethod\":\"key_pool\"}";
    /** Поля SellerProduct: значений ключей в карточке модератора нет и быть не может (ST-01). */
    private static final Set<String> PRODUCT_FIELDS = Set.of("productId", "sellerId", "title", "description", "productType", "platform",
            "activationRegions", "price", "issuanceMethod", "status", "rejectionReason", "blockReason", "submittedAt", "reviewDeadlineAt", "version",
            "createdAt", "updatedAt");

    private static StandHttp gateway;
    private static JdbcTemplate db;
    private static KafkaProducer<String, String> inventory;

    @Autowired
    private KitRedis redis;

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        DataSource migrator = Stand.dataSource("migrator_catalog", "catalog_db");
        db = new JdbcTemplate(migrator);
        inventory = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("inventory-service"), "inventory-service-it");
    }

    @AfterAll
    static void close() {
        inventory.close(Duration.ofSeconds(5));
    }

    // ---------------------------------------------------------------- помощники

    private static final class Seller {
        final UUID userId = UUID.randomUUID();
        final UUID sellerId = UUID.randomUUID();
        final String[] auth;

        Seller() {
            db.update("insert into seller_onboarding.seller_profile (id, user_id, seller_type, name, payout_recipient_name, payout_bank_name, "
                    + "payout_bik, payout_account_number, assortment_description) values (?, ?, 'self_employed', 'Студия «Лунный свет»', "
                    + "'Иванов Иван Иванович', 'Банк «Пример»', '044525000', '40817810000000000001', 'Ключи к инди-играм')", sellerId, userId);
            db.update("update seller_onboarding.seller_profile set status = 'on_review', submitted_at = now(), "
                    + "review_deadline_at = now() + interval '3 days' where id = ?", sellerId);
            db.update("update seller_onboarding.seller_profile set status = 'approved' where id = ?", sellerId);
            auth = StandHttp.bearer(TOKENS.token().subject(userId.toString()).scopes("seller.catalog").roles("seller").amr("pwd", "otp")
                    .sellerId(sellerId.toString()).build());
        }
    }

    private static String[] moderator() {
        return StandHttp.bearer(TOKENS.token().subject(MODERATOR.toString()).scopes("staff.moderation").roles("moderator").amr("pwd", "otp").build());
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

    /** Товар в черновике; название уникально, чтобы поиск находил именно его. */
    private static String draft(Seller seller, String title) throws IOException {
        Response created = gateway.post(SELLER_PRODUCTS, INPUT.replace(TITLE, title), with(seller.auth, "Idempotency-Key", key()));
        assertEquals(201, created.status(), created.body());
        return (String) created.json().get("productId");
    }

    private static Response sellerAction(Seller seller, String productId, String name) throws IOException {
        return gateway.post(SELLER_PRODUCTS + "/" + productId + "/" + name, null, with(seller.auth, "Idempotency-Key", key()));
    }

    private static String queued(Seller seller, String title) throws IOException {
        String productId = draft(seller, title);
        Response submitted = sellerAction(seller, productId, "submit");
        assertEquals(200, submitted.status(), submitted.body());
        assertEquals("on_moderation", submitted.json().get("status"));
        return productId;
    }

    private static String uniqueTitle() {
        return "Лунная тропа " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Response card(String productId) throws IOException {
        return gateway.get(STAFF + "/" + productId, moderator());
    }

    private static Response decide(String productId, String decision, String body, String idempotencyKey) throws IOException {
        return gateway.post(STAFF + "/" + productId + "/" + decision, body, with(moderator(), "Idempotency-Key", idempotencyKey,
                "X-Forwarded-For", MODERATOR_ADDRESS));
    }

    private static Response decide(String productId, String decision, String body) throws IOException {
        return decide(productId, decision, body, key());
    }

    private static Response storefront(String productId, String... headers) throws IOException {
        return gateway.get(STOREFRONT + "/" + productId, headers);
    }

    private static boolean inQueue(String productId) throws IOException {
        String cursor = null;
        for (int page = 0; page < 200; page++) {
            Response response = gateway.get(STAFF + "?limit=100" + (cursor == null ? "" : "&cursor=" + cursor), moderator());
            assertEquals(200, response.status(), response.body());
            for (Object item : (List<?>) response.json().get("items")) {
                if (productId.equals(((Map<?, ?>) item).get("productId"))) {
                    return true;
                }
            }
            cursor = (String) ((Map<?, ?>) response.json().get("page")).get("nextCursor");
            if (cursor == null) {
                return false;
            }
        }
        throw new AssertionError("очередь не закончилась за 200 страниц");
    }

    private static List<Map<String, Object>> outbox(String productId, String eventType) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = ? order by id", String.class,
                productId, eventType)) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    private static boolean await(Duration limit, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    private String cacheKey(String productId) {
        return "catalog:product:" + productId;
    }

    /** Публикует {@code stock.changed} от имени inventory-service; метка времени события задаётся, чтобы собрать нарушенный порядок. */
    private static UUID publishStock(String productId, int available, Instant time) throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productId", productId);
        data.put("available", available);
        data.put("version", 1);
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"" + TraceContext.fresh().traceparent() + "\", \"correlationid\": \""
                + UUID.randomUUID() + "\"}";
        OutgoingEvent event = new EnvelopeBuilder("inventory-service").build(new OutboxRow(1, eventId, "ProductStock", productId, "stock.changed",
                "inventory.events", Json.write(data), headers, time, 0));
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        inventory.send(record).get(20, TimeUnit.SECONDS);
        return eventId;
    }

    private static boolean handled(UUID eventId) {
        return db.queryForObject("select count(*) from public.processed_event where consumer = 'stock-view-service' and event_id = ?", Long.class,
                eventId) == 1;
    }

    private static Integer storedStock(String productId) {
        List<Integer> rows = db.queryForList("select available from catalog.stock_view where product_id = ?::uuid", Integer.class, productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- очередь и карточка

    @Test
    void theQueueAndTheCardShowOnlyProductsTheSellerSubmitted() throws IOException {
        Seller seller = new Seller();
        String title = uniqueTitle();
        String productId = draft(seller, title);

        assertEquals(404, card(productId).status(), "черновик для модератора не существует");
        assertEquals("not-found", code(card(productId)));
        assertFalse(inQueue(productId));

        sellerAction(seller, productId, "submit");
        assertTrue(inQueue(productId));
        Response opened = card(productId);
        assertEquals(200, opened.status(), opened.body());
        assertEquals(PRODUCT_FIELDS, opened.json().keySet(), "в карточке модератора только поля контракта, значений ключей нет (ST-01)");
        assertEquals("on_moderation", opened.json().get("status"));
        assertEquals(title, opened.json().get("title"));
        assertEquals(seller.sellerId.toString(), opened.json().get("sellerId"));
        assertEquals("no-store", opened.header("Cache-Control"));

        Response page = gateway.get(STAFF + "?limit=100", moderator());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.json().get("items");
        assertFalse(items.isEmpty());
        assertEquals(Set.of("productId", "sellerId", "title", "productType", "price", "submittedAt", "reviewDeadlineAt", "overdue"), items.get(0).keySet());
        assertEquals(422, gateway.get(STAFF + "?limit=0", moderator()).status());
        assertEquals(404, card(UUID.randomUUID().toString()).status());
        assertEquals(400, gateway.get(STAFF + "/не-идентификатор", moderator()).status());
    }

    // ---------------------------------------------------------------- одобрить и витрина

    @Test
    void approvePublishesTheProductWithOneEventOneAuditRecordAndTheStorefrontShowsIt() throws IOException {
        Seller seller = new Seller();
        String title = uniqueTitle();
        String productId = queued(seller, title);
        assertEquals(404, storefront(productId).status(), "до одобрения на витрине товара нет");
        assertEquals("not-found", code(storefront(productId)));

        Response approved = decide(productId, "approve", null);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("published", approved.json().get("status"));
        assertNull(approved.json().get("submittedAt"));
        assertFalse(inQueue(productId));

        List<Map<String, Object>> published = outbox(productId, "product.published");
        assertEquals(1, published.size());
        assertEquals(Map.of("productId", productId, "sellerId", seller.sellerId.toString(), "title", title), published.get(0));
        List<Map<String, Object>> audit = outbox(productId, "audit.recorded");
        assertEquals(1, audit.stream().filter(a -> "product.published".equals(a.get("action"))).count());
        Map<String, Object> record = audit.stream().filter(a -> "product.published".equals(a.get("action"))).findFirst().orElseThrow();
        assertEquals("moderator", record.get("actorRole"));
        assertEquals(MODERATOR.toString(), record.get("actorId"));
        assertEquals(MODERATOR_ADDRESS, record.get("actorIp"));
        assertEquals("product", record.get("objectType"));

        Response shown = storefront(productId);
        assertEquals(200, shown.status(), shown.body());
        assertEquals(title, shown.json().get("title"));
        assertEquals(Map.of("amount", 149900, "currency", "RUB"), shown.json().get("price"));
        assertEquals(false, shown.json().get("inStock"), "ключей ещё нет: карточка показывается, покупка недоступна (FT-4.4)");
        assertEquals(0, shown.json().get("maxOrderQuantity"));
        assertNotNull(shown.header("ETag"));
        assertEquals("public, max-age=30", shown.header("Cache-Control"));

        Response search = gateway.get(STOREFRONT + "?q=" + title.substring(title.length() - 8).toLowerCase() + "&limit=5");
        assertEquals(200, search.status(), search.body());
        assertEquals(List.of(productId), ((List<?>) search.json().get("items")).stream().map(i -> ((Map<?, ?>) i).get("productId")).toList());

        assertEquals(404, storefront(UUID.randomUUID().toString()).status());
        assertEquals(400, storefront("НЕ-UUID").status());
    }

    @Test
    void theStorefrontCardSupportsConditionalRequestsAndLivesInRedis() throws Exception {
        Seller seller = new Seller();
        String productId = queued(seller, uniqueTitle());
        decide(productId, "approve", null);

        Response first = storefront(productId);
        String etag = first.header("ETag");
        assertNotNull(etag);
        assertTrue(redis.get(cacheKey(productId)).isPresent(), "карточка легла в Redis под ключом catalog:*");
        assertEquals(etag, storefront(productId).header("ETag"), "из кэша приходит то же тело");
        Response notModified = storefront(productId, "If-None-Match", etag);
        assertEquals(304, notModified.status());
        assertEquals(etag, notModified.header("ETag"));
        assertEquals(200, storefront(productId, "If-None-Match", "\"other\"").status());
    }

    @Test
    void aSellersPriceChangeDropsTheCachedCardSoTheStorefrontShowsTheNewPrice() throws Exception {
        Seller seller = new Seller();
        String title = uniqueTitle();
        String productId = queued(seller, title);
        decide(productId, "approve", null);
        assertEquals(149900, ((Map<?, ?>) storefront(productId).json().get("price")).get("amount"));
        assertTrue(redis.get(cacheKey(productId)).isPresent());

        Response own = gateway.get(SELLER_PRODUCTS + "/" + productId, seller.auth);
        Response changed = gateway.put(SELLER_PRODUCTS + "/" + productId, INPUT.replace(TITLE, title).replace("149900", "99900"),
                with(seller.auth, "If-Match", own.header("ETag")));
        assertEquals(200, changed.status(), changed.body());
        assertEquals("published", changed.json().get("status"), "цена возвращать на модерацию не должна (T7)");
        assertTrue(await(Duration.ofSeconds(10), () -> redis.get(cacheKey(productId)).isEmpty()), "карточка сброшена после фиксации правки");
        assertEquals(99900, ((Map<?, ?>) storefront(productId).json().get("price")).get("amount"));
    }

    // ---------------------------------------------------------------- отклонить

    @Test
    void rejectNeedsAReasonAndTheSellerSeesIt() throws IOException {
        Seller seller = new Seller();
        String productId = queued(seller, uniqueTitle());

        assertEquals(422, decide(productId, "reject", "{}").status());
        assertEquals(422, decide(productId, "reject", "{\"reason\":\"   \"}").status());
        assertEquals(415, gateway.post(STAFF + "/" + productId + "/reject", "причина", with(moderator(), "Idempotency-Key", key(), "Content-Type", "text/plain")).status());
        assertEquals("on_moderation", card(productId).json().get("status"), "ошибка тела решение не принимает");

        Response rejected = decide(productId, "reject", "{\"reason\":\"В описании есть запрещённое содержимое\"}");
        assertEquals(200, rejected.status(), rejected.body());
        assertEquals("rejected", rejected.json().get("status"));
        assertEquals("В описании есть запрещённое содержимое", rejected.json().get("rejectionReason"));

        Response own = gateway.get(SELLER_PRODUCTS + "/" + productId, seller.auth);
        assertEquals("В описании есть запрещённое содержимое", own.json().get("rejectionReason"));
        List<Map<String, Object>> events = outbox(productId, "product.rejected");
        assertEquals(1, events.size());
        assertEquals("В описании есть запрещённое содержимое", events.get(0).get("reason"));

        Response again = decide(productId, "approve", null);
        assertEquals(409, again.status());
        assertEquals("state-conflict", code(again));
        assertEquals("rejected", again.json().get("currentStatus"));
        assertEquals(1, outbox(productId, "product.rejected").size());
        assertEquals(0, outbox(productId, "product.published").size());
    }

    // ---------------------------------------------------------------- заблокировать

    @Test
    void blockTakesThePublishedProductOffTheStorefrontAndTheSellerCanFixAndResubmit() throws Exception {
        Seller seller = new Seller();
        String title = uniqueTitle();
        String productId = queued(seller, title);

        Response early = decide(productId, "block", "{\"reason\":\"Жалоба правообладателя\"}");
        assertEquals(409, early.status(), "блокируется только опубликованный товар (T8)");
        assertEquals("on_moderation", early.json().get("currentStatus"));

        decide(productId, "approve", null);
        assertEquals(200, storefront(productId).status());
        assertTrue(redis.get(cacheKey(productId)).isPresent());

        Response blocked = decide(productId, "block", "{\"reason\":\"Жалоба правообладателя\"}");
        assertEquals(200, blocked.status(), blocked.body());
        assertEquals("blocked", blocked.json().get("status"));
        assertEquals("Жалоба правообладателя", blocked.json().get("blockReason"));
        List<Map<String, Object>> events = outbox(productId, "product.blocked");
        assertEquals(1, events.size());
        assertEquals("moderator_decision", events.get(0).get("cause"));
        assertEquals("Жалоба правообладателя", events.get(0).get("reason"));

        assertTrue(await(Duration.ofSeconds(10), () -> redis.get(cacheKey(productId)).isEmpty()), "карточка сброшена после блокировки");
        assertEquals(404, storefront(productId).status(), "заблокированный товар с витрины исчез");
        Map<String, Object> row = db.queryForMap("select status, status_before_block, block_reason, block_note from catalog.product where id = ?::uuid", productId);
        assertEquals("blocked", row.get("status"));
        assertEquals("published", row.get("status_before_block"));
        assertEquals("moderator_decision", row.get("block_reason"));

        Response own = gateway.get(SELLER_PRODUCTS + "/" + productId, seller.auth);
        assertEquals("Жалоба правообладателя", own.json().get("blockReason"));
        Response fixed = gateway.put(SELLER_PRODUCTS + "/" + productId, INPUT.replace(TITLE, title + " (исправлено)"),
                with(seller.auth, "If-Match", own.header("ETag")));
        assertEquals(200, fixed.status(), fixed.body());
        assertEquals("blocked", fixed.json().get("status"));
        Response resubmitted = sellerAction(seller, productId, "submit");
        assertEquals("on_moderation", resubmitted.json().get("status"), "T11");
        assertTrue(inQueue(productId));

        assertEquals(200, decide(productId, "approve", null).status());
        assertEquals(200, storefront(productId).status(), "после новой модерации товар снова на витрине");
        assertNull(db.queryForObject("select block_note from catalog.product where id = ?::uuid", String.class, productId));
    }

    // ---------------------------------------------------------------- повтор и откат

    @Test
    void theSameIdempotencyKeyGivesTheSameAnswerAndOneEvent() throws IOException {
        Seller seller = new Seller();
        String productId = queued(seller, uniqueTitle());
        String idempotencyKey = key();
        Response first = decide(productId, "approve", null, idempotencyKey);
        Response second = decide(productId, "approve", null, idempotencyKey);
        assertEquals(200, first.status(), first.body());
        assertEquals(200, second.status());
        assertEquals(first.json(), second.json());
        assertEquals("true", second.header("Idempotency-Replayed"));
        assertEquals(1, outbox(productId, "product.published").size());
    }

    @Test
    void aFailureWhileWritingTheAuditRecordRollsBackTheDecisionAndTheEvent() throws IOException {
        Seller seller = new Seller();
        String productId = queued(seller, uniqueTitle());
        Integer versionBefore = db.queryForObject("select version from catalog.product where id = ?::uuid", Integer.class, productId);
        db.execute("create or replace function public.it_refuse_product_audit() returns trigger language plpgsql as $$ begin "
                + "raise exception 'отказ записи аудита для проверки отката'; end $$");
        db.execute("drop trigger if exists it_refuse_product_audit on public.outbox");
        db.execute("create trigger it_refuse_product_audit before insert on public.outbox for each row "
                + "when (new.event_type = 'audit.recorded' and new.aggregate_id = '" + productId + "') execute function public.it_refuse_product_audit()");
        try {
            Response failed = decide(productId, "approve", null);
            assertTrue(failed.status() >= 500, "сбой записи аудита это ошибка сервера: " + failed.status());
            assertEquals("on_moderation", card(productId).json().get("status"), "решение откатилось");
            assertEquals(0, outbox(productId, "product.published").size(), "события решения не осталось");
            assertEquals(0, outbox(productId, "audit.recorded").size());
            assertEquals(versionBefore, db.queryForObject("select version from catalog.product where id = ?::uuid", Integer.class, productId));
            assertEquals(404, storefront(productId).status());
        } finally {
            db.execute("drop trigger if exists it_refuse_product_audit on public.outbox");
            db.execute("drop function if exists public.it_refuse_product_audit()");
        }
        Response retried = decide(productId, "approve", null);
        assertEquals(200, retried.status(), "после устранения сбоя то же решение проходит: " + retried.body());
        assertEquals(1, outbox(productId, "product.published").size());
    }

    // ---------------------------------------------------------------- остаток из stock.changed

    @Test
    void stockChangedUpdatesTheStorefrontAndAnOlderEventDoesNotRollItBack() throws Exception {
        Seller seller = new Seller();
        String productId = queued(seller, uniqueTitle());
        decide(productId, "approve", null);
        assertEquals(false, storefront(productId).json().get("inStock"));
        assertTrue(redis.get(cacheKey(productId)).isPresent());

        Instant now = Instant.now();
        UUID fresh = publishStock(productId, 25, now);
        assertTrue(await(Duration.ofSeconds(30), () -> handled(fresh)), "обработчик разобрал событие");
        assertEquals(25, storedStock(productId));
        assertTrue(await(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(storefront(productId).json().get("inStock"))), "карточка сброшена по событию");
        assertEquals(10, storefront(productId).json().get("maxOrderQuantity"), "в одном заказе не больше десяти (INV-10)");

        UUID late = publishStock(productId, 3, now.minusSeconds(3600));
        assertTrue(await(Duration.ofSeconds(30), () -> handled(late)), "устаревшее событие тоже разобрано и отмечено");
        assertEquals(25, storedStock(productId), "событие с меньшей меткой остаток не откатывает (Р-15)");

        UUID empty = publishStock(productId, 0, now.plusSeconds(60));
        assertTrue(await(Duration.ofSeconds(30), () -> handled(empty)));
        assertEquals(0, storedStock(productId));
        assertTrue(await(Duration.ofSeconds(10), () -> Boolean.FALSE.equals(storefront(productId).json().get("inStock"))));
        Response none = storefront(productId);
        assertEquals(200, none.status(), "нулевой остаток карточку не скрывает (FT-4.4)");
        assertEquals(0, none.json().get("maxOrderQuantity"));
    }

    @Test
    void stockForAnUnknownProductIsIgnoredWithoutRetries() throws Exception {
        UUID event = publishStock(UUID.randomUUID().toString(), 5, Instant.now());
        assertTrue(await(Duration.ofSeconds(30), () -> handled(event)), "событие отмечено обработанным, в недоставленные оно не уходит");
    }

    // ---------------------------------------------------------------- права доступа (ST-02)

    private record Operation(String name, String method, String path) {
    }

    private static final String SOME = "0199e0a0-0000-4000-8000-0000000000dd";
    private static final List<Operation> OPERATIONS = List.of(
            new Operation("listProductModerationQueue", "GET", STAFF),
            new Operation("getProductForModeration", "GET", STAFF + "/" + SOME),
            new Operation("approveProduct", "POST", STAFF + "/" + SOME + "/approve"),
            new Operation("rejectProduct", "POST", STAFF + "/" + SOME + "/reject"),
            new Operation("blockProduct", "POST", STAFF + "/" + SOME + "/block"));

    private static Response call(Operation operation, String[] auth) throws IOException {
        if (operation.method.equals("GET")) {
            return gateway.get(operation.path, auth);
        }
        return gateway.post(operation.path, "{\"reason\":\"причина\"}", with(auth, "Idempotency-Key", key()));
    }

    @Test
    void everyModerationOperationRefusesCallersWithoutTheRight() throws IOException {
        String noScope = TOKENS.token().subject(MODERATOR.toString()).scopes("seller.catalog").roles("moderator").amr("pwd", "otp").build();
        String buyer = TOKENS.token().scopes("staff.moderation").roles("buyer").amr("pwd").build();
        String seller = TOKENS.token().scopes("staff.moderation").roles("seller").amr("pwd", "otp").build();
        String supportOperator = TOKENS.token().scopes("staff.moderation").roles("support-operator").amr("pwd", "otp").build();
        String noSecondFactor = TOKENS.token().subject(MODERATOR.toString()).scopes("staff.moderation").roles("moderator").amr("pwd").build();
        String smsOnly = TOKENS.token().subject(MODERATOR.toString()).scopes("staff.moderation").roles("moderator").amr("sms").build();
        for (Operation operation : OPERATIONS) {
            Response anonymous = call(operation, new String[0]);
            assertEquals(401, anonymous.status(), operation.name + ": без токена");
            assertEquals("unauthenticated", code(anonymous), operation.name);

            assertEquals(403, call(operation, StandHttp.bearer(noScope)).status(), operation.name + ": чужая область");
            for (String wrongRole : List.of(buyer, seller, supportOperator)) {
                Response response = call(operation, StandHttp.bearer(wrongRole));
                assertEquals(403, response.status(), operation.name + ": чужая роль");
                assertEquals("forbidden", code(response), operation.name);
            }
            Response noOtp = call(operation, StandHttp.bearer(noSecondFactor));
            assertEquals(403, noOtp.status(), operation.name + ": без второго фактора");
            assertEquals("second-factor-required", code(noOtp), operation.name);
            Response sms = call(operation, StandHttp.bearer(smsOnly));
            assertEquals(403, sms.status(), operation.name + ": сессия по SMS");
            assertNotNull(code(sms), operation.name);
        }
    }

    @Test
    void theStorefrontCardIsOpenToEveryone() throws IOException {
        String some = UUID.randomUUID().toString();
        String buyer = TOKENS.token().scopes("orders.read").roles("buyer").amr("pwd").build();
        assertEquals(404, storefront(some).status(), "без токена: нет такого товара, а не отказ в доступе");
        assertEquals(404, storefront(some, StandHttp.bearer(buyer)).status());
        assertEquals(404, storefront(some, StandHttp.bearer(TOKENS.token().scopes("staff.moderation").roles("moderator").amr("pwd", "otp").build())).status());
    }
}
