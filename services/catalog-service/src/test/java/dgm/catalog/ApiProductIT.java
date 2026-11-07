package dgm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Товар с выдачей по API продавца на стенде (шаг 9 фазы 5, SM-04, ADR-008, Р-184, US-3.1, US-3.3, US-3.9, FT-4.4): создание с
 * {@code issuanceMethod = seller_api}, запрет смены способа, отказ в отправке и одобрении без действующего подключения
 * ({@code integration-required}), витрина «нет в наличии» при нулевом остатке и появление после {@code stock.changed}, внутренняя карточка
 * для заказов при отключённом подключении. Сервис работает в процессе теста, база и Kafka это набор {@code dev-kit}
 * (общая основа {@link CatalogStandIT}).
 *
 * <p>Подключение системы продавца вставляется строкой в {@code seller_integration}: кабинет подключения проверяет {@code IntegrationIT},
 * здесь важно только то, что каталог видит подключение действующим (статус {@code active}, продавец одобрен).
 */
class ApiProductIT extends CatalogStandIT {

    private static final String SELLER_PRODUCTS = "/api/v1/seller/products";
    private static final String STAFF = "/api/v1/staff/products";
    private static final String STOREFRONT = "/api/v1/products";
    private static final UUID MODERATOR = UUID.fromString("0199e0a0-0000-7000-8000-0000000000b3");
    private static final String MODERATOR_ADDRESS = "203.0.113.10";
    private static final String ENDPOINT = "https://localhost:8443/seller-system";
    private static final String TITLE = "Лунная тропа: ключ Steam";
    private static final String API_INPUT = "{\"title\":\"" + TITLE + "\",\"description\":\"Приключенческая игра, ключ активации Steam\","
            + "\"productType\":\"game_key\",\"platform\":\"Steam\",\"activationRegions\":[\"RU\",\"KZ\"],"
            + "\"price\":{\"amount\":149900,\"currency\":\"RUB\"},\"issuanceMethod\":\"seller_api\"}";
    private static final String POOL_INPUT = API_INPUT.replace("\"seller_api\"", "\"key_pool\"");

    private static StandHttp gateway;
    private static StandHttp orderService;
    private static JdbcTemplate db;
    private static KafkaProducer<String, String> inventory;
    /** Продавцы этого класса: их товары убираются в конце, иначе опубликованные остаются на витрине и ломают проверки соседей. */
    private static final List<UUID> SELLERS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        orderService = StandHttp.asService("order-service", PORT);
        DataSource migrator = Stand.dataSource("migrator_catalog", "catalog_db");
        db = new JdbcTemplate(migrator);
        inventory = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("inventory-service"), "inventory-service-api-it");
    }

    /**
     * Классы каталога делят один стенд, а {@link CatalogServiceIT} ждёт на витрине ровно свои пять товаров. Товары по API здесь
     * публикуются через одобрение, поэтому после класса они удаляются вместе со строками остатка (внешний ключ только у
     * {@code stock_view}; триггеры удаление товара не запрещают).
     */
    @AfterAll
    static void close() {
        inventory.close(Duration.ofSeconds(5));
        for (UUID sellerId : SELLERS) {
            db.update("delete from catalog.stock_view where product_id in (select id from catalog.product where seller_id = ?)", sellerId);
            db.update("delete from catalog.product where seller_id = ?", sellerId);
        }
    }

    // ---------------------------------------------------------------- помощники

    /** Одобренный продавец; подключение системы появляется отдельным вызовом {@link #connect}. */
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
            SELLERS.add(sellerId);
        }

        void connect() {
            db.update("insert into seller_onboarding.seller_integration (seller_id, endpoint_url) values (?, ?)", sellerId, ENDPOINT);
        }

        void disable() {
            db.update("update seller_onboarding.seller_integration set status = 'disabled' where seller_id = ?", sellerId);
        }

        void enable() {
            db.update("update seller_onboarding.seller_integration set status = 'active' where seller_id = ?", sellerId);
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

    private static String uniqueTitle() {
        return "Лунная тропа " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String apiInput(String title) {
        return API_INPUT.replace(TITLE, title);
    }

    private static String productIdOf(Response created) {
        assertEquals(201, created.status(), created.body());
        return (String) created.json().get("productId");
    }

    private static Response create(Seller seller, String input) throws IOException {
        return gateway.post(SELLER_PRODUCTS, input, with(seller.auth, "Idempotency-Key", key()));
    }

    private static Response own(Seller seller, String productId) throws IOException {
        return gateway.get(SELLER_PRODUCTS + "/" + productId, seller.auth);
    }

    private static Response put(Seller seller, String productId, String ifMatch, String input) throws IOException {
        return gateway.put(SELLER_PRODUCTS + "/" + productId, input, with(seller.auth, "If-Match", ifMatch));
    }

    private static Response submit(Seller seller, String productId) throws IOException {
        return gateway.post(SELLER_PRODUCTS + "/" + productId + "/submit", null, with(seller.auth, "Idempotency-Key", key()));
    }

    private static Response approve(String productId) throws IOException {
        return gateway.post(STAFF + "/" + productId + "/approve", null, with(moderator(), "Idempotency-Key", key(), "X-Forwarded-For", MODERATOR_ADDRESS));
    }

    private static Response storefront(String productId) throws IOException {
        return gateway.get(STOREFRONT + "/" + productId);
    }

    private static String statusInDatabase(String productId) {
        return db.queryForObject("select status from catalog.product where id = ?::uuid", String.class, productId);
    }

    private static long outboxCount(String productId) {
        return db.queryForObject("select count(*) from public.outbox where aggregate_id = ?", Long.class, productId);
    }

    private static List<Map<String, Object>> outbox(String productId, String eventType) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = ? order by id", String.class,
                productId, eventType)) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    /** Условие ожидания; обращение к сервису может бросить {@link IOException}. */
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

    /** Публикует {@code stock.changed} от имени inventory-service; метка времени события задаётся, чтобы события шли в нужном порядке. */
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

    /** Опубликованный товар с выдачей по API: подключение есть, отправка и одобрение прошли. */
    private static String publishedApiProduct(Seller seller, String title) throws IOException {
        seller.connect();
        String productId = productIdOf(create(seller, apiInput(title)));
        Response submitted = submit(seller, productId);
        assertEquals(200, submitted.status(), submitted.body());
        Response approved = approve(productId);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("published", approved.json().get("status"));
        return productId;
    }

    // ---------------------------------------------------------------- TC-330: создание

    @Test
    @Tag("TC-330")
    void anApiProductIsCreatedAsADraftWithoutAnIntegrationAndAnnouncedWithItsMethod() throws IOException {
        Seller seller = new Seller();
        String title = uniqueTitle();
        Response created = create(seller, apiInput(title));
        String productId = productIdOf(created);

        assertEquals("draft", created.json().get("status"));
        assertEquals("seller_api", created.json().get("issuanceMethod"));
        assertEquals(1, created.json().get("version"));
        assertEquals("\"1\"", created.header("ETag"));
        assertEquals("seller_api", own(seller, productId).json().get("issuanceMethod"));

        List<Map<String, Object>> events = outbox(productId, "product.created");
        assertEquals(1, events.size());
        assertEquals(Map.of("productId", productId, "sellerId", seller.sellerId.toString(), "productType", "game_key", "issuanceMethod", "seller_api",
                "status", "draft", "version", 1), events.get(0));
        assertEquals(1, outboxCount(productId), "в той же транзакции других событий нет");

        assertEquals(404, storefront(productId).status(), "черновика на витрине нет");
        assertEquals(404, gateway.get(STAFF + "/" + productId, moderator()).status(), "и у модератора нет");
    }

    // ---------------------------------------------------------------- TC-331: способ выдачи не меняется

    @Test
    @Tag("TC-331")
    void theIssuanceMethodNeverChangesInEitherDirectionAndNothingIsWritten() throws IOException {
        Seller seller = new Seller();
        String apiTitle = uniqueTitle();
        String poolTitle = uniqueTitle();
        String api = productIdOf(create(seller, apiInput(apiTitle)));
        String pool = productIdOf(create(seller, POOL_INPUT.replace(TITLE, poolTitle)));
        long apiEvents = outboxCount(api);
        long poolEvents = outboxCount(pool);

        for (Response refused : List.of(put(seller, api, "\"1\"", POOL_INPUT.replace(TITLE, apiTitle)),
                put(seller, pool, "\"1\"", API_INPUT.replace(TITLE, poolTitle)))) {
            assertEquals(422, refused.status(), refused.body());
            assertEquals("validation-failed", code(refused));
            List<?> errors = (List<?>) refused.json().get("errors");
            assertEquals(1, errors.size(), "ошибка одна, на поле способа выдачи");
            assertEquals("/issuanceMethod", ((Map<?, ?>) errors.get(0)).get("pointer"));
            assertEquals("immutable", ((Map<?, ?>) errors.get(0)).get("code"));
        }
        assertEquals(1, db.queryForObject("select version from catalog.product where id = ?::uuid", Integer.class, api));
        assertEquals(1, db.queryForObject("select version from catalog.product where id = ?::uuid", Integer.class, pool));
        assertEquals(apiEvents, outboxCount(api));
        assertEquals(poolEvents, outboxCount(pool));

        Response stale = put(seller, api, "\"7\"", POOL_INPUT.replace(TITLE, apiTitle));
        assertEquals(412, stale.status(), "версия проверяется раньше способа выдачи");
        assertEquals("version-conflict", code(stale));

        Response priceOnly = put(seller, api, "\"1\"", API_INPUT.replace(TITLE, apiTitle).replace("149900", "99900"));
        assertEquals(200, priceOnly.status(), priceOnly.body());
        assertEquals("seller_api", priceOnly.json().get("issuanceMethod"), "правка других полей способ не трогает");
        assertEquals(2, priceOnly.json().get("version"));
    }

    // ---------------------------------------------------------------- TC-332: публикация без подключения

    @Test
    @Tag("TC-332")
    void submitWithoutAnActiveIntegrationIsRefusedWithIntegrationRequiredAndPassesAfterConnecting() throws IOException {
        Seller seller = new Seller();
        String productId = productIdOf(create(seller, apiInput(uniqueTitle())));
        long events = outboxCount(productId);

        Response refused = submit(seller, productId);
        assertEquals(409, refused.status(), refused.body());
        assertEquals("integration-required", code(refused));
        assertEquals("draft", statusInDatabase(productId));
        assertEquals(events, outboxCount(productId), "отказ ничего не записал");

        seller.connect();
        Response queued = submit(seller, productId);
        assertEquals(200, queued.status(), queued.body());
        assertEquals("on_moderation", queued.json().get("status"));

        Response again = submit(seller, productId);
        assertEquals(409, again.status());
        assertEquals("state-conflict", code(again), "товар уже на модерации: статус решает раньше подключения");
    }

    @Test
    @Tag("TC-332")
    void aDisabledIntegrationStopsTheApprovalAndKeyPoolProductsDoNotNeedOne() throws IOException {
        Seller seller = new Seller();
        seller.connect();
        String productId = productIdOf(create(seller, apiInput(uniqueTitle())));
        assertEquals(200, submit(seller, productId).status());

        seller.disable();
        Response refused = approve(productId);
        assertEquals(409, refused.status(), refused.body());
        assertEquals("integration-required", code(refused));
        assertEquals("on_moderation", statusInDatabase(productId), "товар остаётся в очереди");
        assertTrue(outbox(productId, "product.published").isEmpty());

        seller.enable();
        Response approved = approve(productId);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("published", approved.json().get("status"));
        assertEquals(1, outbox(productId, "product.published").size());

        Seller poolSeller = new Seller();
        String pool = productIdOf(create(poolSeller, POOL_INPUT.replace(TITLE, uniqueTitle())));
        assertEquals(200, submit(poolSeller, pool).status(), "пул ключей подключения не требует");
        assertEquals(200, approve(pool).status());
    }

    @Test
    @Tag("TC-332")
    void theInternalCardOfAPublishedApiProductIsBlockedWhileTheIntegrationIsOff() throws IOException {
        Seller seller = new Seller();
        String productId = publishedApiProduct(seller, uniqueTitle());
        Response active = orderService.get("/internal/v1/products/" + productId);
        assertEquals(200, active.status(), active.body());
        assertEquals("published", active.json().get("status"));
        assertEquals("seller_api", active.json().get("issuanceMethod"));

        seller.disable();
        Response off = orderService.get("/internal/v1/products/" + productId);
        assertEquals("blocked", off.json().get("status"), "заказ не оформляется, пока система продавца отключена (F5-16)");
        assertEquals("published", statusInDatabase(productId), "статус товара в базе не менялся: блокировка вычисляется при чтении");

        seller.enable();
        assertEquals("published", orderService.get("/internal/v1/products/" + productId).json().get("status"));
    }

    // ---------------------------------------------------------------- TC-333: витрина и остаток

    @Test
    @Tag("TC-333")
    void aPublishedApiProductIsSoldOutUntilStockChangesAndFollowsTheStockEvents() throws Exception {
        Seller seller = new Seller();
        String title = uniqueTitle();
        String productId = publishedApiProduct(seller, title);

        Response shown = storefront(productId);
        assertEquals(200, shown.status(), shown.body());
        assertEquals(false, shown.json().get("inStock"), "остаток ещё не задан: карточка видна, купить нельзя (FT-4.4, INV-09)");
        assertEquals(0, shown.json().get("maxOrderQuantity"));
        assertNotNull(shown.header("ETag"));

        Response listed = gateway.get(STOREFRONT + "?q=" + title.substring(title.length() - 8).toLowerCase() + "&limit=5");
        assertEquals(200, listed.status(), listed.body());
        List<?> items = (List<?>) listed.json().get("items");
        assertEquals(1, items.size());
        assertEquals(false, ((Map<?, ?>) items.get(0)).get("inStock"));

        Instant now = Instant.now();
        UUID filled = publishStock(productId, 7, now);
        assertTrue(await(Duration.ofSeconds(30), () -> handled(filled)), "обработчик разобрал событие");
        assertTrue(await(Duration.ofSeconds(10), () -> Boolean.TRUE.equals(storefront(productId).json().get("inStock"))), "карточка сброшена по событию");
        assertEquals(7, storefront(productId).json().get("maxOrderQuantity"));
        assertEquals(true, ((Map<?, ?>) ((List<?>) gateway.get(STOREFRONT + "?q=" + title.substring(title.length() - 8).toLowerCase() + "&limit=5").json()
                .get("items")).get(0)).get("inStock"), "список берёт признак из той же копии остатка");

        UUID emptied = publishStock(productId, 0, now.plusSeconds(60));
        assertTrue(await(Duration.ofSeconds(30), () -> handled(emptied)));
        assertTrue(await(Duration.ofSeconds(10), () -> Boolean.FALSE.equals(storefront(productId).json().get("inStock"))));
        assertEquals(200, storefront(productId).status(), "нулевой остаток карточку не скрывает");
        assertFalse(db.queryForList("select available from catalog.stock_view where product_id = ?::uuid", Integer.class, productId).isEmpty());
    }
}
