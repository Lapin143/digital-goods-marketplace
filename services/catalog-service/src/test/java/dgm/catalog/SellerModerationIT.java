package dgm.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Stand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Решения модератора по заявке продавца на стенде (шаг 11 фазы 4, SM-05, переходы T3 – T5, US-2.2, US-2.3, TC-013): очередь и карточка
 * со ссылками на документы, три решения с обязательной причиной, повтор решения, откат решения вместе с событием, права по пяти
 * операциям. Сервис работает в процессе теста, база и хранилище это набор {@code dev-kit} (общая основа {@link CatalogStandIT}).
 *
 * <p>Роль «Продавец» здесь не проверяется: её назначает {@code platform-service} по событию {@code seller.approved}, это сквозная проверка
 * стенда ({@code make sellers-check}). Здесь проверено, что событие и запись аудита в Outbox лежат ровно по одной.
 */
class SellerModerationIT extends CatalogStandIT {

    private static final String BASE = "/api/v1/seller-applications";
    private static final String CURRENT = BASE + "/current";
    private static final String QUEUE = "/api/v1/staff/seller-applications";
    private static final UUID MODERATOR = UUID.fromString("0199e0a0-0000-7000-8000-0000000000b1");
    private static final Duration REVIEW = Duration.ofDays(3);
    private static final Duration PAUSE = Duration.ofHours(24);
    private static final String MODERATOR_ADDRESS = "203.0.113.7";

    private static StandHttp gateway;
    private static JdbcTemplate db;

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        DataSource migrator = Stand.dataSource("migrator_catalog", "catalog_db");
        db = new JdbcTemplate(migrator);
    }

    // ---------------------------------------------------------------- помощники

    /** Заявитель: покупатель с полным входом, свой sub в токене. */
    private static final class Applicant {
        final String id = UUID.randomUUID().toString();
        final String[] auth = StandHttp.bearer(TOKENS.token().subject(id).scopes("seller.apply").roles("buyer").amr("pwd").build());
        String sellerId;
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

    /** Заявитель с полной анкетой и документом; в очередь модератора попадает только после отправки. */
    private static Applicant draft() throws IOException {
        Applicant a = new Applicant();
        assertEquals(201, gateway.post(BASE, SellerOnboardingIT.FORM, with(a.auth, "Idempotency-Key", key())).status());
        String boundary = "----dgm" + UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"справка.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(SellerOnboardingIT.pdf(1024));
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Response document = gateway.request("POST", CURRENT + "/documents", "multipart/form-data; boundary=" + boundary, body.toByteArray(),
                with(a.auth, "Idempotency-Key", key()));
        assertEquals(201, document.status(), document.body());
        a.sellerId = (String) current(a).json().get("sellerId");
        return a;
    }

    private static Applicant onReview() throws IOException {
        Applicant a = draft();
        Response submitted = gateway.post(CURRENT + "/submit", null, with(a.auth, "Idempotency-Key", key()));
        assertEquals(200, submitted.status(), submitted.body());
        assertEquals("on_review", submitted.json().get("status"));
        return a;
    }

    private static Response current(Applicant a) throws IOException {
        return gateway.get(CURRENT, a.auth);
    }

    private static Response card(String sellerId) throws IOException {
        return gateway.get(QUEUE + "/" + sellerId, moderator());
    }

    private static Response decide(String sellerId, String decision, String body) throws IOException {
        return decide(sellerId, decision, body, key());
    }

    private static Response decide(String sellerId, String decision, String body, String idempotencyKey) throws IOException {
        return gateway.post(QUEUE + "/" + sellerId + "/" + decision, body, with(moderator(), "Idempotency-Key", idempotencyKey,
                "X-Forwarded-For", MODERATOR_ADDRESS));
    }

    private static String code(Response response) {
        return (String) response.json().get("code");
    }

    /** Место заявки в очереди, считая со всей очереди постранично; {@code -1}, если её там нет. */
    private static int queuePosition(String sellerId) throws IOException {
        int position = 0;
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 200; pageNumber++) {
            Response page = gateway.get(QUEUE + "?limit=100" + (cursor == null ? "" : "&cursor=" + cursor), moderator());
            assertEquals(200, page.status(), page.body());
            for (Object item : (List<?>) page.json().get("items")) {
                if (sellerId.equals(((Map<?, ?>) item).get("sellerId"))) {
                    return position;
                }
                position++;
            }
            cursor = (String) ((Map<?, ?>) page.json().get("page")).get("nextCursor");
            if (cursor == null) {
                return -1;
            }
        }
        throw new AssertionError("Очередь длиннее 20 000 заявок");
    }

    private static Map<String, Object> queueItem(String sellerId) throws IOException {
        String cursor = null;
        for (int pageNumber = 0; pageNumber < 200; pageNumber++) {
            Response page = gateway.get(QUEUE + "?limit=100" + (cursor == null ? "" : "&cursor=" + cursor), moderator());
            for (Object item : (List<?>) page.json().get("items")) {
                if (sellerId.equals(((Map<?, ?>) item).get("sellerId"))) {
                    return castMap(item);
                }
            }
            cursor = (String) ((Map<?, ?>) page.json().get("page")).get("nextCursor");
            if (cursor == null) {
                break;
            }
        }
        throw new AssertionError("Заявки " + sellerId + " нет в очереди");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    /** Данные событий Outbox по заявке: тип события и тело. */
    private static List<Map<String, Object>> outbox(String sellerId, String eventType) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = ? order by id", String.class,
                sellerId, eventType)) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- очередь и карточка

    @Test
    void queueListsOnlySubmittedApplicationsOldestFirstWithTheirDeadlines() throws IOException {
        Applicant older = onReview();
        Applicant newer = onReview();
        Applicant notSubmitted = draft();
        Instant olderAt = Instant.now().minus(Duration.ofHours(2));
        Instant newerAt = Instant.now().minus(Duration.ofHours(1));
        db.update("update seller_onboarding.seller_profile set submitted_at = ?, review_deadline_at = ? where id = ?::uuid", utc(olderAt),
                utc(olderAt.plus(REVIEW)), older.sellerId);
        db.update("update seller_onboarding.seller_profile set submitted_at = ?, review_deadline_at = ? where id = ?::uuid", utc(newerAt),
                utc(newerAt.plus(REVIEW)), newer.sellerId);

        int first = queuePosition(older.sellerId);
        int second = queuePosition(newer.sellerId);

        assertTrue(first >= 0 && second > first, "старая заявка выше новой: " + first + " и " + second);
        assertEquals(-1, queuePosition(notSubmitted.sellerId), "черновик в очередь не попадает");
        Map<String, Object> item = queueItem(older.sellerId);
        assertEquals("Студия «Лунный свет»", item.get("name"));
        assertEquals("self_employed", item.get("sellerType"));
        assertEquals(1, item.get("documentsCount"));
        assertEquals(Boolean.FALSE, item.get("overdue"));
        long deadlineGap = Math.abs(olderAt.plus(REVIEW).toEpochMilli() - Instant.parse((String) item.get("reviewDeadlineAt")).toEpochMilli());
        assertTrue(deadlineGap <= 1, "срок рассмотрения из очереди: " + item.get("reviewDeadlineAt"));

        db.update("update seller_onboarding.seller_profile set review_deadline_at = ? where id = ?::uuid", utc(Instant.now().minusSeconds(60)),
                older.sellerId);
        assertEquals(Boolean.TRUE, queueItem(older.sellerId).get("overdue"), "просроченная заявка помечена в очереди");
    }

    @Test
    void cardShowsTheFormAndDocumentLinksThatOpenTheStoredFileAndNothingChanges() throws Exception {
        Applicant a = onReview();
        long outboxBefore = db.queryForObject("select count(*) from public.outbox where aggregate_id = ?", Long.class, a.sellerId);

        Response card = card(a.sellerId);

        assertEquals(200, card.status(), card.body());
        assertEquals("no-store", card.header("Cache-Control"));
        assertEquals(a.sellerId, card.json().get("sellerId"));
        assertEquals(a.id, card.json().get("userId"));
        assertEquals("on_review", card.json().get("status"));
        assertEquals("Студия «Лунный свет»", card.json().get("name"));
        assertEquals("044525000", ((Map<?, ?>) card.json().get("payoutDetails")).get("bik"));
        List<?> documents = (List<?>) card.json().get("documents");
        assertEquals(1, documents.size());
        Map<?, ?> link = (Map<?, ?>) ((Map<?, ?>) documents.get(0)).get("link");
        String url = (String) link.get("url");
        assertTrue(url.startsWith("https://localhost:8443/files/seller-documents/"), url);
        long left = Duration.between(Instant.now(), Instant.parse((String) link.get("expiresAt"))).toSeconds();
        assertTrue(left > 270 && left <= 300, "ссылка на 5 минут, осталось " + left + " с");
        SellerOnboardingIT.Fetched file = SellerOnboardingIT.fetch(url);
        assertEquals(200, file.status(), "ссылка карточки открывается без токена");
        assertArrayEquals(SellerOnboardingIT.pdf(1024), file.body());

        assertEquals("on_review", current(a).json().get("status"), "открытие карточки заявку не меняет");
        assertEquals(outboxBefore, db.queryForObject("select count(*) from public.outbox where aggregate_id = ?", Long.class, a.sellerId));
    }

    @Test
    void draftAndUnknownApplicationsAreNotFoundForTheModerator() throws IOException {
        Applicant notSubmitted = draft();

        Response draftCard = card(notSubmitted.sellerId);
        assertEquals(404, draftCard.status());
        assertEquals("not-found", code(draftCard));
        assertEquals(404, card(UUID.randomUUID().toString()).status());
        Response decision = decide(notSubmitted.sellerId, "approve", null);
        assertEquals(404, decision.status(), "черновик одобрить нельзя: для модератора его нет");
        assertEquals("draft", current(notSubmitted).json().get("status"));
        assertEquals(400, gateway.get(QUEUE + "/не-идентификатор", moderator()).status());
    }

    // ---------------------------------------------------------------- решения

    @Tag("TC-013")
    @Test
    void approvalWritesExactlyOneEventAndOneAuditRecordAndRepeatingItChangesNothing() throws IOException {
        Applicant a = onReview();
        String idempotencyKey = key();

        Response approved = decide(a.sellerId, "approve", null, idempotencyKey);

        assertEquals(200, approved.status(), approved.body());
        assertEquals("approved", approved.json().get("status"));
        assertEquals("no-store", approved.header("Cache-Control"));
        assertEquals("approved", current(a).json().get("status"), "заявитель видит решение");
        assertEquals(-1, queuePosition(a.sellerId), "решённая заявка уходит из очереди");

        List<Map<String, Object>> events = outbox(a.sellerId, "seller.approved");
        assertEquals(1, events.size(), "одно событие решения");
        assertEquals(Map.of("sellerId", a.sellerId, "userId", a.id, "moderatorId", MODERATOR.toString(), "sellerType", "self_employed"), events.get(0));
        List<Map<String, Object>> audit = outbox(a.sellerId, "audit.recorded");
        assertEquals(1, audit.size(), "одна запись аудита");
        Map<String, Object> record = audit.get(0);
        assertEquals(MODERATOR.toString(), record.get("actorId"));
        assertEquals("moderator", record.get("actorRole"));
        assertEquals("seller.approved", record.get("action"));
        assertEquals(a.sellerId, record.get("objectId"));
        assertEquals(MODERATOR_ADDRESS, record.get("actorIp"), "адрес сотрудника из X-Forwarded-For шлюза");
        assertEquals(List.of(Map.of("field", "status", "personal", false, "before", "on_review", "after", "approved")), record.get("changes"));
        assertFalse(record.toString().contains("Иванов"), "персональных данных в аудите нет");

        Response replay = decide(a.sellerId, "approve", null, idempotencyKey);
        assertEquals(200, replay.status());
        assertEquals("true", replay.header("Idempotency-Replayed"));
        Response second = decide(a.sellerId, "approve", null);
        assertEquals(409, second.status(), "повторное решение другим запросом");
        assertEquals("state-conflict", code(second));
        assertEquals("approved", second.json().get("currentStatus"));
        assertEquals(409, decide(a.sellerId, "reject", "{\"reason\":\"Поздно\"}").status(), "решённую заявку не отклонить");
        assertEquals(1, outbox(a.sellerId, "seller.approved").size());
        assertEquals(1, outbox(a.sellerId, "audit.recorded").size());
        assertEquals(0, outbox(a.sellerId, "seller.rejected").size());
    }

    @Tag("TC-013")
    @Test
    void rejectionNeedsAReasonStartsThePauseAndWritesTheReasonIntoEventAndAudit() throws IOException {
        Applicant a = onReview();
        String reason = "Документы не читаются " + UUID.randomUUID();

        Response missing = decide(a.sellerId, "reject", "{}");
        assertEquals(422, missing.status());
        assertEquals("/reason", ((Map<?, ?>) ((List<?>) missing.json().get("errors")).get(0)).get("pointer"));
        assertEquals(422, decide(a.sellerId, "reject", "{\"reason\":\"   \"}").status(), "из одних пробелов причина пустая");
        assertEquals(422, decide(a.sellerId, "reject", "{\"reason\":\"" + "я".repeat(1001) + "\"}").status(), "длиннее 1000 знаков");
        assertEquals(422, decide(a.sellerId, "reject", "{\"comment\":\"не то поле\"}").status());
        assertEquals("on_review", current(a).json().get("status"), "отказы по форме заявку не меняют");
        assertEquals(0, outbox(a.sellerId, "seller.rejected").size());
        assertEquals(0, outbox(a.sellerId, "audit.recorded").size());

        Instant before = Instant.now();
        Response rejected = decide(a.sellerId, "reject", "{\"reason\":\"  " + reason + "  \"}");

        assertEquals(200, rejected.status(), rejected.body());
        assertEquals("rejected", rejected.json().get("status"));
        assertEquals(reason, rejected.json().get("decisionReason"), "пробелы по краям убраны");
        Instant nextAt = Instant.parse((String) rejected.json().get("nextApplicationAt"));
        assertTrue(nextAt.isAfter(before.plus(PAUSE).minusSeconds(5)) && nextAt.isBefore(Instant.now().plus(PAUSE).plusSeconds(5)), "пауза 24 часа: " + nextAt);
        Map<String, Object> event = outbox(a.sellerId, "seller.rejected").get(0);
        assertEquals(reason, event.get("reason"));
        assertEquals(nextAt.toEpochMilli(), Instant.parse((String) event.get("nextApplicationAt")).toEpochMilli());
        assertEquals(1, outbox(a.sellerId, "audit.recorded").size());
        List<?> changes = (List<?>) outbox(a.sellerId, "audit.recorded").get(0).get("changes");
        assertTrue(changes.toString().contains(reason), "причина решения в аудите: " + changes);
        assertEquals("rejected", current(a).json().get("status"));
    }

    @Tag("TC-013")
    @Test
    void returnNeedsACommentLeavesNoPauseAndTheApplicantResubmits() throws IOException {
        Applicant a = onReview();
        String comment = "Уточните реквизиты " + UUID.randomUUID();

        assertEquals(422, decide(a.sellerId, "return", "{}").status());
        assertEquals(422, decide(a.sellerId, "return", "{\"reason\":\"не то поле\"}").status());

        Response returned = decide(a.sellerId, "return", "{\"comment\":\"" + comment + "\"}");

        assertEquals(200, returned.status(), returned.body());
        assertEquals("returned", returned.json().get("status"));
        assertEquals(comment, returned.json().get("decisionReason"));
        assertNull(returned.json().get("nextApplicationAt"), "после доработки паузы нет");
        assertEquals(comment, outbox(a.sellerId, "seller.returned").get(0).get("comment"));
        assertEquals(1, outbox(a.sellerId, "audit.recorded").size());

        Response seen = current(a);
        Response fixed = gateway.put(CURRENT, SellerOnboardingIT.FORM.replace("044525000", "044525225"), with(a.auth, "If-Match", seen.header("ETag")));
        assertEquals(200, fixed.status(), fixed.body());
        Response resubmitted = gateway.post(CURRENT + "/submit", null, with(a.auth, "Idempotency-Key", key()));
        assertEquals(200, resubmitted.status(), resubmitted.body());
        assertEquals("on_review", resubmitted.json().get("status"));
        assertTrue(queuePosition(a.sellerId) >= 0, "после повторной отправки заявка снова в очереди");
        assertEquals(200, decide(a.sellerId, "approve", null).status());
    }

    @Test
    void overdueApplicationCanStillBeDecidedAndNobodyDecidesItTwice() throws IOException {
        Applicant a = onReview();
        Instant submittedAt = Instant.now().minus(Duration.ofDays(4));
        db.update("update seller_onboarding.seller_profile set submitted_at = ?, review_deadline_at = ?, overdue_marked_at = now() where id = ?::uuid",
                utc(submittedAt), utc(submittedAt.plus(REVIEW)), a.sellerId);

        Response rejected = decide(a.sellerId, "reject", "{\"reason\":\"Не успели вовремя, но документы не подходят\"}");

        assertEquals(200, rejected.status(), rejected.body());
        assertEquals(409, decide(a.sellerId, "return", "{\"comment\":\"Уже решено\"}").status());
        assertEquals("rejected", current(a).json().get("status"));
    }

    // ---------------------------------------------------------------- откат решения

    @Test
    void failureWhileWritingTheAuditRecordRollsBackTheDecisionAndTheEvent() throws IOException {
        Applicant a = onReview();
        Integer versionBefore = db.queryForObject("select version from seller_onboarding.seller_profile where id = ?::uuid", Integer.class, a.sellerId);
        db.execute("create or replace function public.it_refuse_audit() returns trigger language plpgsql as $$ begin "
                + "raise exception 'отказ записи аудита для проверки отката'; end $$");
        db.execute("drop trigger if exists it_refuse_audit on public.outbox");
        db.execute("create trigger it_refuse_audit before insert on public.outbox for each row "
                + "when (new.event_type = 'audit.recorded' and new.aggregate_id = '" + a.sellerId + "') execute function public.it_refuse_audit()");
        try {
            Response failed = decide(a.sellerId, "approve", null);

            assertTrue(failed.status() >= 500, "сбой записи аудита это ошибка сервера: " + failed.status());
            assertEquals("on_review", current(a).json().get("status"), "решение откатилось");
            assertEquals(0, outbox(a.sellerId, "seller.approved").size(), "события решения не осталось");
            assertEquals(0, outbox(a.sellerId, "audit.recorded").size());
            assertEquals(versionBefore, db.queryForObject("select version from seller_onboarding.seller_profile where id = ?::uuid", Integer.class, a.sellerId),
                    "версия профиля прежняя: строка не обновлялась");
            assertNull(db.queryForObject("select decision_reason from seller_onboarding.seller_profile where id = ?::uuid", String.class, a.sellerId));
        } finally {
            db.execute("drop trigger if exists it_refuse_audit on public.outbox");
            db.execute("drop function if exists public.it_refuse_audit()");
        }

        Response retried = decide(a.sellerId, "approve", null);
        assertEquals(200, retried.status(), "после устранения сбоя то же решение проходит: " + retried.body());
        assertEquals(1, outbox(a.sellerId, "seller.approved").size());
        assertEquals(1, outbox(a.sellerId, "audit.recorded").size());
    }

    // ---------------------------------------------------------------- права доступа (ST-02)

    private record Operation(String name, String method, String path) {
    }

    private static final String SOME = "0199e0a0-0000-4000-8000-0000000000cc";
    private static final List<Operation> OPERATIONS = List.of(
            new Operation("listSellerApplicationQueue", "GET", QUEUE),
            new Operation("getSellerApplicationCard", "GET", QUEUE + "/" + SOME),
            new Operation("approveSellerApplication", "POST", QUEUE + "/" + SOME + "/approve"),
            new Operation("rejectSellerApplication", "POST", QUEUE + "/" + SOME + "/reject"),
            new Operation("returnSellerApplication", "POST", QUEUE + "/" + SOME + "/return"));

    private static Response call(Operation operation, String[] auth) throws IOException {
        if (operation.method.equals("GET")) {
            return gateway.get(operation.path, auth);
        }
        return gateway.post(operation.path, "{\"reason\":\"причина\",\"comment\":\"комментарий\"}", with(auth, "Idempotency-Key", key()));
    }

    @Test
    void everyModerationOperationRefusesCallersWithoutTheRight() throws IOException {
        String noScope = TOKENS.token().subject(MODERATOR.toString()).scopes("seller.apply").roles("moderator").amr("pwd", "otp").build();
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
}
