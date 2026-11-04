package dgm.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dgm.catalog.sellers.apikey.domain.ApiKeyValues;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Concurrent;
import dgm.kit.testing.Stand;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * API-ключи продавца на стенде (шаг 4 среза 1 фазы 5, US-11.1, ADR-027, Р-185, Р-198, INV-41, INV-43): выпуск показывает значение один
 * раз, в базе остаётся только SHA-256, повтор по тому же {@code Idempotency-Key} отдаёт ответ без значения, отзыв необратим и
 * идемпотентен, шлюз проверяет ключ по хешу, шесть выпусков одновременно дают пять ключей и один отказ, а значения ключа нет ни в таблице,
 * ни в Outbox, ни в таблице идемпотентности. Сервис работает в процессе теста, база это набор {@code dev-kit} (общая основа
 * {@link CatalogStandIT}).
 */
class ApiKeyIT extends CatalogStandIT {

    private static final String BASE = "/api/v1/seller/api-keys";
    private static final String CHECK = "/internal/v1/api-keys/check";
    private static final Set<String> TABLE_COLUMNS = Set.of("id", "seller_id", "name", "key_hash", "fingerprint", "status", "created_at", "revoked_at");

    private static StandHttp gateway;
    private static JdbcTemplate db;

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        DataSource migrator = Stand.dataSource("migrator_catalog", "catalog_db");
        db = new JdbcTemplate(migrator);
    }

    // ---------------------------------------------------------------- помощники

    /** Продавец: профиль {@code approved} (или черновик), токен с областью, ролью, вторым фактором и claim seller_id. */
    private static final class Seller {
        final UUID userId = UUID.randomUUID();
        final UUID sellerId = UUID.randomUUID();
        final String[] auth;

        Seller(boolean approved) {
            db.update("insert into seller_onboarding.seller_profile (id, user_id, seller_type, name, payout_recipient_name, payout_bank_name, "
                    + "payout_bik, payout_account_number, assortment_description) values (?, ?, 'self_employed', 'Студия «Лунный свет»', "
                    + "'Иванов Иван Иванович', 'Банк «Пример»', '044525000', '40817810000000000001', 'Ключи к инди-играм')", sellerId, userId);
            if (approved) {
                db.update("update seller_onboarding.seller_profile set status = 'on_review', submitted_at = now(), "
                        + "review_deadline_at = now() + interval '3 days' where id = ?", sellerId);
                db.update("update seller_onboarding.seller_profile set status = 'approved' where id = ?", sellerId);
            }
            auth = StandHttp.bearer(TOKENS.token().subject(userId.toString()).scopes("seller.catalog").roles("seller").amr("pwd", "otp")
                    .sellerId(sellerId.toString()).build());
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

    private static String nameBody(String name) {
        return Json.write(Map.of("name", name));
    }

    private static Response issue(Seller seller, String name) throws IOException {
        return gateway.post(BASE, nameBody(name), with(seller.auth, "Idempotency-Key", key()));
    }

    private static Response revoke(Seller seller, String apiKeyId) throws IOException {
        return gateway.post(BASE + "/" + apiKeyId + "/revocation", null, with(seller.auth, "Idempotency-Key", key()));
    }

    private static Response list(Seller seller, String query) throws IOException {
        return gateway.get(BASE + query, seller.auth);
    }

    /** Проверка ключа так, как её вызывает шлюз: по SHA-256 значения, с сертификатом {@code api-gateway}. */
    private static Response check(String value) throws IOException {
        return gateway.post(CHECK, Json.write(Map.of("keyHash", HexFormat.of().formatHex(ApiKeyValues.hash(value)))));
    }

    private static String code(Response response) {
        return (String) response.json().get("code");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> apiKey(Response issued) {
        return (Map<String, Object>) issued.json().get("apiKey");
    }

    private static String issuedValue(Response issued) {
        return (String) issued.json().get("value");
    }

    private static List<Map<String, Object>> rows(Seller seller) {
        return db.queryForList("select id::text as id, name, fingerprint, status, key_hash, revoked_at from seller_onboarding.api_key "
                + "where seller_id = ? order by created_at, id", seller.sellerId);
    }

    private static long count(Seller seller, String status) {
        return db.queryForObject("select count(*) from seller_onboarding.api_key where seller_id = ? and status = ?", Long.class, seller.sellerId,
                status);
    }

    /** События Outbox по ключу: тема, тело и заголовки, в порядке записи. */
    private static List<Map<String, Object>> outbox(String aggregateId, String eventType) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList("select topic, payload::text as payload from public.outbox "
                + "where aggregate_id = ? and event_type = ? order by id", aggregateId, eventType)) {
            Map<String, Object> event = new LinkedHashMap<>(Json.parseObject((String) row.get("payload")));
            event.put("$topic", row.get("topic"));
            events.add(event);
        }
        return events;
    }

    /** Сколько мест в базе содержат строку: строка ключа, Outbox (тело и заголовки), ответы таблицы идемпотентности. */
    private static long occurrences(String text) {
        long inKeys = db.queryForObject("select count(*) from seller_onboarding.api_key t where strpos(t::text, ?) > 0", Long.class, text);
        long inOutbox = db.queryForObject("select count(*) from public.outbox where strpos(payload::text, ?) > 0 or strpos(headers::text, ?) > 0",
                Long.class, text, text);
        long inIdempotency = db.queryForObject("select count(*) from public.idempotency_key "
                + "where strpos(coalesce(response_body::text, ''), ?) > 0 or strpos(coalesce(response_headers::text, ''), ?) > 0", Long.class, text, text);
        return inKeys + inOutbox + inIdempotency;
    }

    // ---------------------------------------------------------------- TC-950: выпуск

    @Test
    @Tag("TC-950")
    void issueShowsTheValueOnceAndTheTableKeepsOnlyTheHash() throws Exception {
        Seller seller = new Seller(true);
        Response issued = issue(seller, "Склад");

        assertEquals(201, issued.status(), issued.body());
        assertEquals("no-store", issued.header("Cache-Control"));
        assertEquals(BASE, issued.header("Location"));
        assertEquals(Set.of("apiKey", "value", "shownOnce"), issued.json().keySet());
        assertEquals(true, issued.json().get("shownOnce"));
        String value = issuedValue(issued);
        assertTrue(ApiKeyValues.isWellFormed(value), value);
        Map<String, Object> key = apiKey(issued);
        assertEquals("Склад", key.get("name"));
        assertEquals("active", key.get("status"));
        assertEquals(ApiKeyValues.fingerprint(value), key.get("fingerprint"));
        assertNull(key.get("revokedAt"));

        List<Map<String, Object>> stored = rows(seller);
        assertEquals(1, stored.size());
        assertEquals(key.get("id"), stored.get(0).get("id"));
        assertEquals("active", stored.get(0).get("status"));
        byte[] hash = (byte[]) stored.get(0).get("key_hash");
        assertEquals(32, hash.length);
        assertArrayEquals(ApiKeyValues.hash(value), hash);

        // Таблица не имеет столбца, в котором мог бы лежать открытый ключ
        List<String> columns = db.queryForList("select column_name from information_schema.columns where table_schema = 'seller_onboarding' "
                + "and table_name = 'api_key'", String.class);
        assertEquals(TABLE_COLUMNS, Set.copyOf(columns));
    }

    @Test
    @Tag("TC-950")
    void theSameIdempotencyKeyRepeatsTheAnswerWithoutTheValueAndIssuesNoSecondKey() throws Exception {
        Seller seller = new Seller(true);
        String[] headers = with(seller.auth, "Idempotency-Key", key());
        Response first = gateway.post(BASE, nameBody("Склад"), headers);
        Response second = gateway.post(BASE, nameBody("Склад"), headers);

        assertEquals(201, first.status(), first.body());
        assertEquals(201, second.status(), second.body());
        assertEquals("true", second.header("Idempotency-Replayed"));
        assertEquals(Set.of("apiKey", "shownOnce"), second.json().keySet(), "повтор не несёт значения");
        assertEquals(apiKey(first), apiKey(second));
        assertFalse(second.body().contains(issuedValue(first)));
        assertEquals(1, rows(seller).size());
        assertEquals(1, outbox((String) apiKey(first).get("id"), "api-key.issued").size());
    }

    @Test
    @Tag("TC-950")
    void issuingWritesTheEventAndTheAuditRecordWithoutTheValueInTheSameTransaction() throws Exception {
        Seller seller = new Seller(true);
        Response issued = issue(seller, "Склад");
        assertEquals(201, issued.status(), issued.body());
        String id = (String) apiKey(issued).get("id");

        List<Map<String, Object>> events = outbox(id, "api-key.issued");
        assertEquals(1, events.size());
        assertEquals("catalog.events", events.get(0).get("$topic"));
        assertEquals(Map.of("apiKeyId", id, "sellerId", seller.sellerId.toString(), "$topic", "catalog.events"), events.get(0));

        List<Map<String, Object>> audits = outbox(id, "audit.recorded");
        assertEquals(1, audits.size());
        Map<String, Object> audit = audits.get(0);
        assertEquals("audit.events", audit.get("$topic"));
        assertEquals("seller.api_key_issued", audit.get("action"));
        assertEquals("seller", audit.get("actorRole"));
        assertEquals(seller.userId.toString(), audit.get("actorId"));
        assertEquals("api-key", audit.get("objectType"));
        assertEquals(id, audit.get("objectId"));
        assertEquals("", audit.get("actorIp"));
    }

    @Test
    @Tag("TC-950")
    void theListShowsTheKeysNewestFirstWithoutTheValueAndPagesWithTheCursor() throws Exception {
        Seller seller = new Seller(true);
        List<String> ids = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Response issued = issue(seller, "Ключ " + i);
            assertEquals(201, issued.status(), issued.body());
            ids.add((String) apiKey(issued).get("id"));
            values.add(issuedValue(issued));
        }
        Collections.reverse(ids);

        Response all = list(seller, "");
        assertEquals(200, all.status(), all.body());
        assertEquals("no-store", all.header("Cache-Control"));
        assertEquals(ids, items(all).stream().map(i -> (String) i.get("id")).toList());
        for (String value : values) {
            assertFalse(all.body().contains(value), "значение ключа в списке");
        }

        Response first = list(seller, "?limit=2");
        assertEquals(2, items(first).size());
        String cursor = (String) ((Map<?, ?>) first.json().get("page")).get("nextCursor");
        assertNotNull(cursor);
        Response second = list(seller, "?limit=2&cursor=" + cursor);
        assertEquals(List.of(ids.get(2)), items(second).stream().map(i -> (String) i.get("id")).toList());
        assertNull(((Map<?, ?>) second.json().get("page")).get("nextCursor"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Response page) {
        return (List<Map<String, Object>>) page.json().get("items");
    }

    // ---------------------------------------------------------------- TC-951: отзыв и проверка шлюзом

    @Test
    @Tag("TC-951")
    void revocationMakesTheKeyRevokedOnceAndTheGatewayCheckStopsAcceptingIt() throws Exception {
        Seller seller = new Seller(true);
        Response issued = issue(seller, "Склад");
        String id = (String) apiKey(issued).get("id");
        String value = issuedValue(issued);

        Response before = check(value);
        assertEquals(200, before.status(), before.body());
        assertEquals(Map.of("valid", true, "apiKeyId", id, "sellerId", seller.sellerId.toString(), "sellerStatus", "approved"), before.json());

        Response revoked = revoke(seller, id);
        assertEquals(200, revoked.status(), revoked.body());
        assertEquals("revoked", revoked.json().get("status"));
        String revokedAt = (String) revoked.json().get("revokedAt");
        assertNotNull(revokedAt);
        assertEquals("revoked", rows(seller).get(0).get("status"));
        assertNotNull(rows(seller).get(0).get("revoked_at"));

        Response after = check(value);
        assertEquals(200, after.status(), after.body());
        assertEquals(java.util.Map.of("valid", false), withoutNulls(after.json()));

        Response repeated = revoke(seller, id);
        assertEquals(200, repeated.status(), repeated.body());
        assertEquals(revokedAt, repeated.json().get("revokedAt"), "дата первого отзыва не сдвигается");
        assertEquals(1, outbox(id, "api-key.revoked").size(), "повторный отзыв события не пишет");
        List<Map<String, Object>> audits = outbox(id, "audit.recorded");
        assertEquals(List.of("seller.api_key_issued", "seller.api_key_revoked"), audits.stream().map(a -> (String) a.get("action")).toList());

        Response revokedList = list(seller, "?status=revoked");
        assertEquals(List.of(id), items(revokedList).stream().map(i -> (String) i.get("id")).toList());
        assertEquals(List.of(), items(list(seller, "?status=active")));
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> map) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(map);
        copy.values().removeIf(java.util.Objects::isNull);
        return copy;
    }

    @Test
    @Tag("TC-951")
    void anotherSellersKeyAndAnUnknownKeyAreNotFoundAndTheKeyStaysActive() throws Exception {
        Seller owner = new Seller(true);
        Seller other = new Seller(true);
        String id = (String) apiKey(issue(owner, "Склад")).get("id");

        Response foreign = revoke(other, id);
        Response unknown = revoke(other, UUID.randomUUID().toString());
        Response malformed = revoke(other, "not-an-id");

        for (Response response : List.of(foreign, unknown, malformed)) {
            assertEquals(404, response.status(), response.body());
            assertEquals("not-found", code(response));
        }
        assertEquals("active", rows(owner).get(0).get("status"));
        assertEquals(List.of(), outbox(id, "api-key.revoked"));
        assertEquals(List.of(), items(list(other, "")), "чужой ключ не виден в списке");
    }

    @Test
    @Tag("TC-951")
    void theGatewayCheckGivesTheSameAnswerForAnUnknownKeyAndRefusesOtherCallers() throws Exception {
        Response unknown = check(ApiKeyValues.generate(length -> new byte[length]));
        assertEquals(200, unknown.status(), unknown.body());
        assertEquals(java.util.Map.of("valid", false), withoutNulls(unknown.json()));
        assertEquals(Set.of("valid", "apiKeyId", "sellerId", "sellerStatus"), unknown.json().keySet());

        StandHttp other = StandHttp.asService("order-service", PORT);
        Response refused = other.post(CHECK, Json.write(Map.of("keyHash", "a".repeat(64))));
        assertEquals(403, refused.status(), refused.body());
        Response bad = gateway.post(CHECK, "{\"keyHash\":\"не хеш\"}");
        assertEquals(422, bad.status(), bad.body());
        assertEquals("validation-failed", code(bad));
    }

    // ---------------------------------------------------------------- TC-952: предел, статус продавца, ключом нельзя

    @Test
    @Tag("TC-952")
    void ofSixParallelIssuesFiveSucceedAndOneGets409WithTheLimit() throws Exception {
        Seller seller = new Seller(true);
        List<Response> answers = Concurrent.race(6, index -> issue(seller, "Ключ " + index));

        List<Integer> statuses = new ArrayList<>(answers.stream().map(Response::status).toList());
        Collections.sort(statuses);
        assertEquals(List.of(201, 201, 201, 201, 201, 409), statuses);
        Response refused = answers.stream().filter(a -> a.status() == 409).findFirst().orElseThrow();
        assertEquals("api-key-limit", code(refused));
        assertEquals(5, refused.json().get("limit"));

        assertEquals(5, count(seller, "active"));
        assertEquals(5, rows(seller).size());
        long distinct = db.queryForObject("select count(distinct key_hash) from seller_onboarding.api_key where seller_id = ?", Long.class,
                seller.sellerId);
        assertEquals(5, distinct);
        long events = db.queryForObject("select count(*) from public.outbox where event_type = 'api-key.issued' and payload->>'sellerId' = ?",
                Long.class, seller.sellerId.toString());
        assertEquals(5, events, "отказавшему выпуску события нет");
    }

    @Test
    @Tag("TC-952")
    void revokingAKeyFreesAPlaceForANewOne() throws Exception {
        Seller seller = new Seller(true);
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add((String) apiKey(issue(seller, "Ключ " + i)).get("id"));
        }
        assertEquals(409, issue(seller, "Шестой").status());
        assertEquals(200, revoke(seller, ids.get(0)).status());
        Response sixth = issue(seller, "Шестой");
        assertEquals(201, sixth.status(), sixth.body());
        assertEquals(5, count(seller, "active"));
        assertEquals(1, count(seller, "revoked"));
    }

    @Test
    @Tag("TC-952")
    void aSellerWhoseApplicationIsNotApprovedCannotIssueOrListKeys() throws Exception {
        Seller draft = new Seller(false);
        Response issued = issue(draft, "Склад");
        assertEquals(403, issued.status(), issued.body());
        assertEquals("forbidden", code(issued));
        assertEquals(403, list(draft, "").status());
        assertEquals(List.of(), rows(draft));
    }

    @Test
    @Tag("TC-952")
    void aKeyCannotIssueListOrRevokeKeysEvenWhenTheGatewayVouchesForIt() throws Exception {
        Seller seller = new Seller(true);
        String id = (String) apiKey(issue(seller, "Склад")).get("id");
        String[] asKey = {"X-Seller-Id", seller.sellerId.toString(), "X-Auth-Kind", "api-key"};

        Response issued = gateway.post(BASE, nameBody("Из ключа"), with(asKey, "Idempotency-Key", key()));
        Response listed = gateway.get(BASE, asKey);
        Response revoked = gateway.post(BASE + "/" + id + "/revocation", null, with(asKey, "Idempotency-Key", key()));

        for (Response response : List.of(issued, listed, revoked)) {
            assertEquals(401, response.status(), response.body());
            assertEquals("unauthenticated", code(response));
        }
        assertEquals(1, rows(seller).size());
        assertEquals("active", rows(seller).get(0).get("status"));
    }

    @Test
    @Tag("TC-952")
    void aTokenWithoutTheCatalogScopeOrWithoutTheSecondFactorIsRefused() throws Exception {
        Seller seller = new Seller(true);
        String[] noScope = StandHttp.bearer(TOKENS.token().subject(seller.userId.toString()).scopes("seller.apply").roles("seller")
                .amr("pwd", "otp").sellerId(seller.sellerId.toString()).build());
        String[] noOtp = StandHttp.bearer(TOKENS.token().subject(seller.userId.toString()).scopes("seller.catalog").roles("seller")
                .amr("pwd").sellerId(seller.sellerId.toString()).build());

        Response scope = gateway.post(BASE, nameBody("Склад"), with(noScope, "Idempotency-Key", key()));
        Response factor = gateway.post(BASE, nameBody("Склад"), with(noOtp, "Idempotency-Key", key()));

        assertEquals(403, scope.status(), scope.body());
        assertEquals(403, factor.status(), factor.body());
        assertEquals("second-factor-required", code(factor));
        assertEquals(List.of(), rows(seller));
    }

    @Test
    @Tag("TC-952")
    void issuingWithoutAnIdempotencyKeyIsRefusedWith400() throws Exception {
        Seller seller = new Seller(true);
        Response response = gateway.post(BASE, nameBody("Склад"), seller.auth);
        assertEquals(400, response.status(), response.body());
        assertEquals("idempotency-key-required", code(response));
        assertEquals(List.of(), rows(seller));
    }

    // ---------------------------------------------------------------- TC-953: значения нет нигде

    @Test
    @Tag("TC-953")
    void theValueOfTheKeyIsNowhereInTheTableTheOutboxOrTheIdempotencyStore() throws Exception {
        Seller seller = new Seller(true);
        String[] headers = with(seller.auth, "Idempotency-Key", key());
        Response issued = gateway.post(BASE, nameBody("Склад"), headers);
        assertEquals(201, issued.status(), issued.body());
        String value = issuedValue(issued);
        String id = (String) apiKey(issued).get("id");
        String secretPart = value.substring(ApiKeyValues.PREFIX.length());

        // Повтор, список, отзыв и проверка: каждый из них мог бы унести значение в свою запись
        gateway.post(BASE, nameBody("Склад"), headers);
        list(seller, "");
        check(value);
        assertEquals(200, revoke(seller, id).status());

        assertEquals(0, occurrences(value), "значение ключа найдено в базе");
        assertEquals(0, occurrences(secretPart), "значение без префикса найдено в базе");
        // Хеш хранится как байты, а в текстовом виде его нет ни в событиях, ни в ответах
        String hex = HexFormat.of().formatHex(ApiKeyValues.hash(value));
        long hexInEvents = db.queryForObject("select count(*) from public.outbox where strpos(payload::text, ?) > 0", Long.class, hex);
        assertEquals(0, hexInEvents, "хеш ключа в событии");
        // Проверка не пустая: ответ выпуска в таблице идемпотентности есть и содержит идентификатор ключа
        long stored = db.queryForObject("select count(*) from public.idempotency_key where state = 'completed' and strpos(response_body::text, ?) > 0",
                Long.class, id);
        assertTrue(stored >= 1, "ответ выпуска не найден в таблице идемпотентности");
    }

    /** Событие журнала одной строкой: имя, сообщение, поля MDC и цепочка исключений. */
    private static String render(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getLoggerName()).append(' ').append(event.getFormattedMessage());
        for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
            text.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        for (IThrowableProxy error = event.getThrowableProxy(); error != null; error = error.getCause()) {
            text.append(' ').append(error.getClassName()).append(": ").append(error.getMessage());
        }
        return text.toString();
    }

    @Test
    @Tag("TC-953")
    void theValueOfTheKeyIsNotInTheServiceLogEvenWithTheLoudestLevels() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Map<String, Level> loud = new LinkedHashMap<>();
        loud.put("dgm", Level.TRACE);
        loud.put("org.springframework.jdbc", Level.TRACE);
        loud.put("org.springframework.web", Level.DEBUG);
        loud.put("org.springframework.security", Level.DEBUG);
        loud.put("org.springframework.transaction", Level.DEBUG);
        Map<String, Level> before = new LinkedHashMap<>();
        for (Map.Entry<String, Level> entry : loud.entrySet()) {
            Logger logger = context.getLogger(entry.getKey());
            before.put(entry.getKey(), logger.getLevel());
            logger.setLevel(entry.getValue());
        }
        ListAppender<ILoggingEvent> journal = new ListAppender<>();
        journal.setContext(context);
        journal.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(journal);

        Seller seller = new Seller(true);
        String value;
        String id;
        try {
            String[] headers = with(seller.auth, "Idempotency-Key", key());
            Response issued = gateway.post(BASE, nameBody("Склад"), headers);
            assertEquals(201, issued.status(), issued.body());
            value = issuedValue(issued);
            id = (String) apiKey(issued).get("id");
            // Повтор, список, проверка, значение вместо хеша (ошибка разбора) и отзыв
            gateway.post(BASE, nameBody("Склад"), headers);
            list(seller, "");
            check(value);
            Response wrongField = gateway.post(CHECK, Json.write(Map.of("keyHash", value)));
            assertEquals(422, wrongField.status(), wrongField.body());
            assertFalse(wrongField.body().contains(value), "ответ об ошибке повторяет значение ключа");
            assertEquals(200, revoke(seller, id).status());
        } finally {
            root.detachAppender(journal);
            journal.stop();
            for (Map.Entry<String, Level> entry : before.entrySet()) {
                context.getLogger(entry.getKey()).setLevel(entry.getValue());
            }
        }

        List<ILoggingEvent> events = new ArrayList<>(journal.list);
        assertFalse(events.isEmpty(), "контроль: перехват журнала работает");
        assertTrue(events.stream().anyMatch(e -> render(e).contains(id)), "контроль: журнал доступа назвал отзываемый ключ по идентификатору");
        String secretPart = value.substring(ApiKeyValues.PREFIX.length());
        for (ILoggingEvent event : events) {
            String text = render(event);
            assertFalse(text.contains(value), "значение ключа в журнале: " + event.getLoggerName());
            assertFalse(text.contains(secretPart), "значение ключа без префикса в журнале: " + event.getLoggerName());
        }
    }
}
