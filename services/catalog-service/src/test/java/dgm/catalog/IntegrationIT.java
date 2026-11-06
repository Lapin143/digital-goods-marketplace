package dgm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.client.EndpointGuard;
import dgm.kit.json.Json;
import dgm.kit.testing.Stand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
 * Подключение системы продавца на стенде (шаг 6 среза 1 фазы 5, US-11.3, ADR-028, Р-187, Р-198, U21): адрес проходит защиту от SSRF на
 * настоящем DNS системы, секрет показывается один раз и нигде не хранится, смена секрета увеличивает версию, повтор смены по тому же
 * {@code Idempotency-Key} секрета не содержит, внутренний адрес открыт только {@code delivery-service} и {@code platform-service}, а аудит несёт
 * только версии, статусы и хеши частей адреса, а секретов нет ни в таблицах, ни в журнале сервиса. Адрес лежит в таблице открытым (так его читает
 * платформа), поэтому журнал JDBC с параметрами на уровне TRACE его показать может, и тест журнала ищет только секреты. Сервис работает в процессе теста, база это набор {@code dev-kit} (общая основа
 * {@link CatalogStandIT}); стенд освобождает от проверки диапазонов имя {@code localhost}.
 */
class IntegrationIT extends CatalogStandIT {

    private static final String BASE = "/api/v1/seller/integration";
    private static final String ROTATION = BASE + "/secret-rotation";
    private static final String URL = "https://localhost:8443/dgm/hook?token=in-path-token";
    private static final Set<String> TABLE_COLUMNS = Set.of("seller_id", "endpoint_url", "secret_version", "status", "version", "created_at", "updated_at");

    private static StandHttp gateway;
    private static StandHttp delivery;
    private static StandHttp platform;
    private static JdbcTemplate db;

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        delivery = StandHttp.asService("delivery-service", PORT);
        platform = StandHttp.asService("platform-service", PORT);
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

    private static String bodyOf(String url) {
        return Json.write(Map.of("endpointUrl", url));
    }

    private static Response read(Seller seller) throws IOException {
        return gateway.get(BASE, seller.auth);
    }

    private static Response save(Seller seller, String ifMatch, String body) throws IOException {
        return gateway.put(BASE, body, ifMatch == null ? seller.auth : with(seller.auth, "If-Match", ifMatch));
    }

    private static Response rotate(Seller seller, String idempotencyKey) throws IOException {
        return gateway.post(ROTATION, null, with(seller.auth, "Idempotency-Key", idempotencyKey));
    }

    private static String code(Response response) {
        return (String) response.json().get("code");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> integration(Response saved) {
        return (Map<String, Object>) saved.json().get("integration");
    }

    private static List<Map<String, Object>> rows(Seller seller) {
        return db.queryForList("select seller_id::text as seller_id, endpoint_url, secret_version, status, version from seller_onboarding.seller_integration "
                + "where seller_id = ?", seller.sellerId);
    }

    /** Записи аудита по продавцу: тело события в порядке записи. */
    private static List<Map<String, Object>> audits(Seller seller, String action) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = 'audit.recorded' order by id",
                String.class, seller.sellerId.toString())) {
            Map<String, Object> data = Json.parseObject(payload);
            if (action.equals(data.get("action"))) {
                found.add(data);
            }
        }
        return found;
    }

    /** Сколько мест в базе содержат строку: строка подключения, Outbox (тело и заголовки), ответы таблицы идемпотентности. */
    private static long occurrences(String text) {
        long inRows = db.queryForObject("select count(*) from seller_onboarding.seller_integration t where strpos(t::text, ?) > 0", Long.class, text);
        long inOutbox = db.queryForObject("select count(*) from public.outbox where strpos(payload::text, ?) > 0 or strpos(headers::text, ?) > 0",
                Long.class, text, text);
        long inIdempotency = db.queryForObject("select count(*) from public.idempotency_key "
                + "where strpos(coalesce(response_body::text, ''), ?) > 0 or strpos(coalesce(response_headers::text, ''), ?) > 0", Long.class, text, text);
        return inRows + inOutbox + inIdempotency;
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- TC-954: адрес

    @Test
    @Tag("TC-954")
    void thereIsNoConnectionUntilTheFirstSaveAndTheFirstSaveStoresTheAddressAndShowsTheSecret() throws Exception {
        Seller seller = new Seller(true);
        Response before = read(seller);
        assertEquals(200, before.status(), before.body());
        assertEquals("\"0\"", before.header("ETag"));
        assertNull(before.json().get("endpointUrl"));
        assertEquals(0, ((Number) before.json().get("version")).intValue());
        assertEquals(0, rows(seller).size());

        Response saved = save(seller, "\"0\"", bodyOf(URL));
        assertEquals(200, saved.status(), saved.body());
        assertEquals("no-store", saved.header("Cache-Control"));
        assertEquals("\"1\"", saved.header("ETag"));
        assertEquals(Set.of("integration", "secret"), saved.json().keySet());
        assertEquals(URL, integration(saved).get("endpointUrl"));
        assertEquals("active", integration(saved).get("status"));
        assertTrue(((String) saved.json().get("secret")).matches("[0-9a-f]{64}"), saved.body());

        List<Map<String, Object>> stored = rows(seller);
        assertEquals(1, stored.size());
        assertEquals(URL, stored.get(0).get("endpoint_url"));
        assertEquals(1, ((Number) stored.get(0).get("secret_version")).intValue());
        assertEquals(1, ((Number) stored.get(0).get("version")).intValue());
        // В таблице нет столбца, в котором мог бы лежать секрет
        List<String> columns = db.queryForList("select column_name from information_schema.columns where table_schema = 'seller_onboarding' "
                + "and table_name = 'seller_integration'", String.class);
        assertEquals(TABLE_COLUMNS, Set.copyOf(columns));

        Response after = read(seller);
        assertEquals("\"1\"", after.header("ETag"));
        assertEquals(URL, after.json().get("endpointUrl"));
        assertFalse(after.body().contains((String) saved.json().get("secret")));
    }

    @Test
    @Tag("TC-954")
    void addressesThePlatformMustNeverCallAreRefusedWith422AndTheSameTextAndNothingIsStored() throws Exception {
        Seller seller = new Seller(true);
        String tooLong = "https://localhost:8443/" + "a".repeat(EndpointGuard.MAX_LENGTH);
        List<String> refused = List.of("http://localhost:8443/dgm", "https://127.0.0.1:8443/dgm", "https://10.0.0.5/dgm", "https://192.168.1.10/dgm",
                "https://169.254.169.254/latest/meta-data", "https://[::1]/dgm", "https://2130706433/dgm", "https://localhost:8080/dgm",
                "https://user:secret@localhost:8443/dgm", "https://localhost:8443/dgm#fragment", "https://localhost:8443/a b", "https://no-such-name.invalid/dgm",
                "HTTPS://localhost:8443/dgm", tooLong);
        String text = null;
        for (String url : refused) {
            Response response = save(seller, "\"0\"", bodyOf(url));
            assertEquals(422, response.status(), url + " " + response.body());
            assertEquals("endpoint-not-allowed", code(response), url);
            String detail = (String) response.json().get("detail");
            text = text == null ? detail : text;
            assertEquals(text, detail, "текст отказа один и тот же: " + url);
            assertFalse(response.body().contains("10.0.0.5") || response.body().contains("169.254"), "ответ повторяет адрес: " + url);
        }
        assertEquals(0, rows(seller).size());
        assertEquals(0, audits(seller, "seller.integration_set").size());
    }

    @Test
    @Tag("TC-954")
    void theVersionOfTheRecordGuardsEverySaveAndAMissingIfMatchIs428() throws Exception {
        Seller seller = new Seller(true);
        assertEquals(428, save(seller, null, bodyOf(URL)).status());
        assertEquals(412, save(seller, "\"7\"", bodyOf(URL)).status());
        assertEquals(400, save(seller, "0", bodyOf(URL)).status());
        assertEquals(0, rows(seller).size());

        assertEquals(200, save(seller, "\"0\"", bodyOf(URL)).status());
        // Потерянный ответ первого PUT: повтор с тем же If-Match это 412, подключения-двойника и второго показа секрета нет
        Response repeat = save(seller, "\"0\"", bodyOf(URL));
        assertEquals(412, repeat.status(), repeat.body());
        assertEquals("version-conflict", code(repeat));
        assertEquals(1, rows(seller).size());

        Response second = save(seller, "\"1\"", bodyOf("https://localhost:8443/other"));
        assertEquals(200, second.status(), second.body());
        assertEquals("\"2\"", second.header("ETag"));
        assertEquals(Set.of("integration"), second.json().keySet());
        // Тот же адрес и статус с актуальной версией: ничего не меняется, версия прежняя
        Response same = save(seller, "\"2\"", bodyOf("https://localhost:8443/other"));
        assertEquals(200, same.status(), same.body());
        assertEquals("\"2\"", same.header("ETag"));
    }

    @Test
    @Tag("TC-954")
    void aSellerWhoIsNotApprovedCannotReadSaveOrRotateAndTheKeyOfAnotherSellerOpensNothing() throws Exception {
        Seller draft = new Seller(false);
        assertEquals(403, read(draft).status());
        assertEquals(403, save(draft, "\"0\"", bodyOf(URL)).status());
        assertEquals(403, rotate(draft, key()).status());
        assertEquals(0, rows(draft).size());
        // Без токена: 401 на шлюзе или в сервисе, подключение не читается
        assertEquals(401, gateway.get(BASE).status());
    }

    // ---------------------------------------------------------------- TC-959: секрет

    @Test
    @Tag("TC-959")
    void rotationRaisesTheVersionShowsTheNewSecretOnceAndTheRepeatByTheSameKeyHasNoSecret() throws Exception {
        Seller seller = new Seller(true);
        String first = (String) save(seller, "\"0\"", bodyOf(URL)).json().get("secret");
        String idempotencyKey = key();

        Response rotated = rotate(seller, idempotencyKey);
        assertEquals(200, rotated.status(), rotated.body());
        assertEquals("no-store", rotated.header("Cache-Control"));
        assertEquals(Set.of("secretVersion", "secret", "shownOnce"), rotated.json().keySet());
        assertEquals(2, ((Number) rotated.json().get("secretVersion")).intValue());
        String second = (String) rotated.json().get("secret");
        assertTrue(second.matches("[0-9a-f]{64}"), second);
        assertNotEquals(first, second);

        Response repeat = rotate(seller, idempotencyKey);
        assertEquals(200, repeat.status(), repeat.body());
        assertEquals("true", repeat.header("Idempotency-Replayed"));
        assertEquals(Set.of("secretVersion", "shownOnce"), repeat.json().keySet());
        assertFalse(repeat.body().contains(second));

        List<Map<String, Object>> stored = rows(seller);
        assertEquals(1, stored.size());
        assertEquals(2, ((Number) stored.get(0).get("secret_version")).intValue());
        assertEquals(2, ((Number) stored.get(0).get("version")).intValue());
        assertEquals(1, audits(seller, "seller.integration_secret_rotated").size(), "повтор не создал второй записи аудита");

        // Следующее сохранение секрета не показывает, версия секрета остаётся второй
        Response later = save(seller, "\"2\"", bodyOf("https://localhost:8443/other"));
        assertEquals(200, later.status(), later.body());
        assertEquals(Set.of("integration"), later.json().keySet());
        assertEquals(2, ((Number) integration(later).get("secretVersion")).intValue());

        // Нигде в базе секретов нет: ни в строке, ни в Outbox, ни в ответах таблицы идемпотентности
        assertEquals(0, occurrences(first), "первый секрет найден в базе");
        assertEquals(0, occurrences(second), "второй секрет найден в базе");
        assertTrue(occurrences("https://localhost:8443/other") > 0, "контроль: поиск находит адрес в строке подключения");
    }

    @Test
    @Tag("TC-959")
    void rotationWithoutAConnectionIs409() throws Exception {
        Seller seller = new Seller(true);
        Response response = rotate(seller, key());
        assertEquals(409, response.status(), response.body());
        assertEquals("state-conflict", code(response));
        assertEquals(0, rows(seller).size());
        assertEquals(400, gateway.post(ROTATION, null, seller.auth).status(), "без Idempotency-Key смена не выполняется");
    }

    @Test
    @Tag("TC-959")
    void theInternalAddressIsOpenOnlyToDeliveryAndPlatformAndIsTheSame404WhenThereIsNothingToCall() throws Exception {
        Seller seller = new Seller(true);
        String path = "/internal/v1/sellers/" + seller.sellerId + "/integration";
        assertEquals(404, delivery.get(path).status(), "подключения нет");

        String secret = (String) save(seller, "\"0\"", bodyOf(URL)).json().get("secret");
        for (StandHttp caller : List.of(delivery, platform)) {
            Response response = caller.get(path);
            assertEquals(200, response.status(), response.body());
            assertEquals(Set.of("sellerId", "endpointUrl", "status", "secretVersion"), response.json().keySet());
            assertEquals(seller.sellerId.toString(), response.json().get("sellerId"));
            assertEquals(URL, response.json().get("endpointUrl"));
            assertEquals(1, ((Number) response.json().get("secretVersion")).intValue());
            assertFalse(response.body().contains(secret), "в ответе для сервисов секрета нет");
        }
        assertEquals(403, gateway.get(path).status(), "шлюз не входит в список вызывающих");
        assertEquals(404, delivery.get("/internal/v1/sellers/not-an-id/integration").status());

        assertEquals(200, save(seller, "\"1\"", Json.write(Map.of("endpointUrl", URL, "status", "disabled"))).status());
        assertEquals(404, delivery.get(path).status(), "подключение отключено");
        assertEquals(200, save(seller, "\"2\"", Json.write(Map.of("endpointUrl", URL, "status", "active"))).status());
        assertEquals(200, delivery.get(path).status());

        // R1 блокировки продавца не знает: переходы T8 и T9 придут в R2 миграцией, и из «одобрен» триггер никуда не пускает. Поэтому
        // профиль без одобрения берём готовым: подключение вставлено строкой в обход кабинета (кабинет не одобренному продавцу его не даёт)
        Seller pending = new Seller(false);
        db.update("insert into seller_onboarding.seller_integration (seller_id, endpoint_url) values (?, ?)", pending.sellerId, URL);
        assertEquals(404, delivery.get("/internal/v1/sellers/" + pending.sellerId + "/integration").status(), "профиль продавца не одобрен");
        assertEquals(200, delivery.get(path).status(), "контроль: у одобренного продавца с тем же подключением ответ 200");
    }

    // ---------------------------------------------------------------- TC-960: аудит

    @Test
    @Tag("TC-960")
    void theAuditCarriesVersionsStatusesAndHashesOfTheAddressPartsButNeverTheSecretOrTheAddress() throws Exception {
        Seller seller = new Seller(true);
        String first = (String) save(seller, "\"0\"", bodyOf(URL)).json().get("secret");
        String second = (String) rotate(seller, key()).json().get("secret");

        List<Map<String, Object>> saves = audits(seller, "seller.integration_set");
        assertEquals(1, saves.size());
        Map<String, Object> audit = saves.get(0);
        assertEquals("seller", audit.get("actorRole"));
        assertEquals(seller.userId.toString(), audit.get("actorId"));
        assertEquals("seller-integration", audit.get("objectType"));
        assertEquals(seller.sellerId.toString(), audit.get("objectId"));
        assertEquals("", audit.get("actorIp"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) audit.get("changes");
        assertEquals(List.of("endpointHost", "endpointPath", "status", "secretVersion"), changes.stream().map(c -> (String) c.get("field")).toList());
        assertEquals(sha256("localhost"), changes.get(0).get("after"));
        assertEquals(sha256("/dgm/hook?token=in-path-token"), changes.get(1).get("after"));

        List<Map<String, Object>> rotations = audits(seller, "seller.integration_secret_rotated");
        assertEquals(1, rotations.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rotation = (List<Map<String, Object>>) rotations.get(0).get("changes");
        assertEquals(1, rotation.size());
        assertEquals(1, ((Number) rotation.get(0).get("before")).intValue());
        assertEquals(2, ((Number) rotation.get(0).get("after")).intValue());

        // Ни секретов, ни адреса, ни его частей, ни токена из пути в событиях нет
        for (String forbidden : List.of(first, second, URL, "localhost", "in-path-token", "/dgm/hook")) {
            long found = db.queryForObject("select count(*) from public.outbox where aggregate_id = ? and (strpos(payload::text, ?) > 0 "
                    + "or strpos(headers::text, ?) > 0)", Long.class, seller.sellerId.toString(), forbidden, forbidden);
            assertEquals(0, found, "в Outbox найдено: " + (forbidden.length() > 12 ? forbidden.substring(0, 12) + "…" : forbidden));
        }
    }

    // ---------------------------------------------------------------- журнал сервиса

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
    @Tag("TC-960")
    void theSecretsAreNotInTheServiceLogEvenWithTheLoudestLevels() throws Exception {
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
        String first;
        String second;
        try {
            first = (String) save(seller, "\"0\"", bodyOf(URL)).json().get("secret");
            assertNotNull(first);
            second = (String) rotate(seller, key()).json().get("secret");
            read(seller);
            save(seller, "\"2\"", bodyOf("https://127.0.0.1:8443/dgm"));
            delivery.get("/internal/v1/sellers/" + seller.sellerId + "/integration");
        } finally {
            root.detachAppender(journal);
            journal.stop();
            for (Map.Entry<String, Level> entry : before.entrySet()) {
                context.getLogger(entry.getKey()).setLevel(entry.getValue());
            }
        }

        List<ILoggingEvent> events = new ArrayList<>(journal.list);
        assertFalse(events.isEmpty(), "контроль: перехват журнала работает");
        assertTrue(events.stream().anyMatch(e -> render(e).contains(seller.sellerId.toString())),
                "контроль: журнал доступа называет продавца по идентификатору");
        for (ILoggingEvent event : events) {
            String text = render(event);
            assertFalse(text.contains(first), "секрет в журнале: " + event.getLoggerName());
            assertFalse(text.contains(second), "секрет в журнале: " + event.getLoggerName());
        }
    }
}
