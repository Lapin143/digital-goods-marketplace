package dgm.catalog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.service.ModerationDeadlineJob;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.secret.SecretFiles;
import dgm.kit.storage.ObjectStorage;
import dgm.kit.storage.client.S3ObjectStorage;
import dgm.kit.storage.client.S3Settings;
import dgm.kit.testing.Stand;
import dgm.kit.tls.TlsMaterial;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Заявка продавца на стенде (шаг 10 фазы 4, SM-05): жизненный цикл анкеты с ETag и ключами идемпотентности, документы в настоящем
 * объектном хранилище (тип по содержимому, размеры, предел числа), подписанная ссылка на 5 минут, пауза после отказа, доработка без
 * паузы, однократная пометка просрочки и матрица прав по семи операциям. Сервис работает в процессе теста, хранилище и база это
 * набор {@code dev-kit} с отладочными портами (общая основа {@link CatalogStandIT}).
 *
 * <p>Переходы заявки, которые делает модератор (шаг 11), здесь выполняются прямой правкой строки под ролью мигратора: проверку
 * переходов в базе при этом выполняет настоящий триггер миграции.
 */
class SellerOnboardingIT extends CatalogStandIT {

    private static final String BASE = "/api/v1/seller-applications";
    private static final String CURRENT = BASE + "/current";
    private static final String FORM = """
            {"sellerType":"self_employed","name":"Студия «Лунный свет»",
             "payoutDetails":{"recipientName":"Иванов Иван Иванович","bankName":"Банк «Пример»","bik":"044525000","accountNumber":"40817810000000000001"},
             "assortmentDescription":"Ключи к инди-играм для Steam"}""";
    private static final Duration PAUSE = Duration.ofHours(24);
    private static final Duration REVIEW = Duration.ofDays(3);

    private static StandHttp gateway;
    private static JdbcTemplate db;

    @Autowired
    private ModerationDeadlineJob deadlineJob;

    @BeforeAll
    static void sellerClients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        DataSource migrator = Stand.dataSource("migrator_catalog", "catalog_db");
        db = new JdbcTemplate(migrator);
    }

    // ---------------------------------------------------------------- помощники

    /** Заявитель: покупатель с полным входом, свой sub в токене. */
    private static final class Applicant {
        final String id = UUID.randomUUID().toString();
        final String[] auth = StandHttp.bearer(TOKENS.token().subject(id).scopes("seller.apply").roles("buyer").amr("pwd").build());
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

    private static Response create(Applicant a, String json) throws IOException {
        return gateway.post(BASE, json, with(a.auth, "Idempotency-Key", key()));
    }

    private static Response current(Applicant a) throws IOException {
        return gateway.get(CURRENT, a.auth);
    }

    private static Response replace(Applicant a, String etag, String json) throws IOException {
        return gateway.put(CURRENT, json, with(a.auth, "If-Match", etag));
    }

    private static Response submit(Applicant a) throws IOException {
        return gateway.post(CURRENT + "/submit", null, with(a.auth, "Idempotency-Key", key()));
    }

    private static Response upload(Applicant a, String fileName, String declaredType, byte[] content) throws IOException {
        return upload(a, key(), fileName, declaredType, content);
    }

    private static Response upload(Applicant a, String idempotencyKey, String fileName, String declaredType, byte[] content) throws IOException {
        // Граница выводится из ключа: повтор с тем же ключом и тем же файлом даёт то же тело, иначе это «ключ с другим телом» (422)
        String boundary = "----dgm" + idempotencyKey.replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + declaredType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return gateway.request("POST", CURRENT + "/documents", "multipart/form-data; boundary=" + boundary, body.toByteArray(),
                with(a.auth, "Idempotency-Key", idempotencyKey));
    }

    private static Response link(Applicant a, String documentId) throws IOException {
        return gateway.get(CURRENT + "/documents/" + documentId + "/link", a.auth);
    }

    /** Заявитель с полной анкетой и одним документом, ещё в черновике. */
    private static String draftWithDocument(Applicant a) throws IOException {
        assertEquals(201, create(a, FORM).status());
        Response doc = upload(a, "справка.pdf", "application/pdf", pdf(1024));
        assertEquals(201, doc.status(), doc.body());
        return (String) doc.json().get("documentId");
    }

    private static byte[] pdf(int size) {
        return content("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), size);
    }

    private static byte[] jpeg(int size) {
        return content(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, size);
    }

    private static byte[] png(int size) {
        return content(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, size);
    }

    /** Начало заданного типа и двоичный «хвост» до нужного размера: случайные байты со всеми значениями, включая CR, LF и дефисы. */
    private static byte[] content(byte[] head, int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        System.arraycopy(head, 0, bytes, 0, Math.min(head.length, size));
        return bytes;
    }

    private static String code(Response response) {
        return (String) response.json().get("code");
    }

    private static List<String> pointers(Response response) {
        List<String> pointers = new ArrayList<>();
        Object errors = response.json().get("errors");
        if (errors instanceof List<?> list) {
            for (Object item : list) {
                pointers.add((String) ((Map<?, ?>) item).get("pointer"));
            }
        }
        return pointers;
    }

    private static List<?> documents(Response response) {
        return (List<?>) response.json().get("documents");
    }

    private static Instant instant(Response response, String field) {
        return Instant.parse((String) response.json().get(field));
    }

    private static String sellerId(Response response) {
        return (String) response.json().get("sellerId");
    }

    /** Время для столбца timestamptz без сдвигов часового пояса JVM. */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- жизненный цикл анкеты

    @Test
    void applicationGoesFromDraftToReviewWithEtagAndDeadline() throws IOException {
        Applicant a = new Applicant();
        assertEquals(404, current(a).status(), "профиля ещё нет");

        Response created = create(a, "{\"sellerType\":\"self_employed\",\"name\":\"Студия\"}");
        assertEquals(201, created.status(), created.body());
        assertEquals(CURRENT, created.header("Location"));
        assertEquals("draft", created.json().get("status"));
        assertEquals(List.of(), documents(created));
        assertTrue(created.header("ETag").matches("\"[0-9]+\""), created.header("ETag"));
        assertEquals("no-store", created.header("Cache-Control"));
        assertNull(created.json().get("nextApplicationAt"));

        Response read = current(a);
        assertEquals(200, read.status());
        assertEquals(created.header("ETag"), read.header("ETag"));
        assertEquals(sellerId(created), sellerId(read));

        Response saved = replace(a, read.header("ETag"), FORM);
        assertEquals(200, saved.status(), saved.body());
        assertNotEquals(read.header("ETag"), saved.header("ETag"));
        assertEquals("draft", saved.json().get("status"));
        Map<?, ?> payout = (Map<?, ?>) saved.json().get("payoutDetails");
        assertEquals("044525000", payout.get("bik"));

        Response stale = replace(a, read.header("ETag"), FORM);
        assertEquals(412, stale.status());
        assertEquals("version-conflict", code(stale));
        assertEquals(428, gateway.put(CURRENT, FORM, a.auth).status(), "без If-Match");
        assertEquals("precondition-required", code(gateway.put(CURRENT, FORM, a.auth)));

        Response noDocuments = submit(a);
        assertEquals(422, noDocuments.status());
        assertEquals(List.of("/documents"), pointers(noDocuments));

        assertEquals(201, upload(a, "справка.pdf", "application/pdf", pdf(2048)).status());
        Response submitted = submit(a);
        assertEquals(200, submitted.status(), submitted.body());
        assertEquals("on_review", submitted.json().get("status"));
        assertNotEquals(saved.header("ETag"), submitted.header("ETag"));
        assertEquals(REVIEW, Duration.between(instant(submitted, "submittedAt"), instant(submitted, "reviewDeadlineAt")),
                "срок рассмотрения 3 суток от подачи");
        assertTrue(Duration.between(instant(submitted, "submittedAt"), Instant.now()).abs().toSeconds() < 60);

        Response edit = replace(a, submitted.header("ETag"), FORM);
        assertEquals(409, edit.status(), "на проверке анкету не правят");
        assertEquals("state-conflict", code(edit));
        assertEquals("on_review", edit.json().get("currentStatus"));

        Response again = create(a, FORM);
        assertEquals(409, again.status(), "второй профиль не создаётся (INV-39)");
        assertEquals("on_review", again.json().get("currentStatus"));
        assertEquals(409, submit(a).status(), "повторная подача на проверке");
    }

    @Test
    void submitListsEveryMissingField() throws IOException {
        Applicant a = new Applicant();
        assertEquals(201, create(a, "{\"name\":\"Студия\"}").status());
        Response rejected = submit(a);
        assertEquals(422, rejected.status());
        assertEquals("validation-failed", code(rejected));
        List<String> pointers = pointers(rejected);
        assertTrue(pointers.containsAll(List.of("/sellerType", "/payoutDetails/recipientName", "/payoutDetails/bankName",
                "/payoutDetails/bik", "/payoutDetails/accountNumber", "/assortmentDescription", "/documents")), pointers.toString());
        assertFalse(pointers.contains("/name"));
        Object first = ((List<?>) rejected.json().get("errors")).get(0);
        assertEquals("required", ((Map<?, ?>) first).get("code"));
    }

    @Test
    void invalidFormIsRefusedWithPointers() throws IOException {
        Applicant a = new Applicant();
        Response wrongFormat = create(a, """
                {"payoutDetails":{"bik":"12","accountNumber":"abc"},"name":""}""");
        assertEquals(422, wrongFormat.status());
        assertTrue(pointers(wrongFormat).containsAll(List.of("/payoutDetails/bik", "/payoutDetails/accountNumber", "/name")));

        Response unknown = create(a, "{\"sellerType\":\"martian\"}");
        assertEquals(422, unknown.status());
        assertEquals(List.of("/sellerType"), pointers(unknown));

        Response extra = create(a, "{\"role\":\"admin\"}");
        assertEquals(422, extra.status(), "лишнее поле не принимается молча");

        Response notJson = gateway.request("POST", BASE, "text/plain", "name=x".getBytes(StandardCharsets.UTF_8),
                with(a.auth, "Idempotency-Key", key()));
        assertEquals(415, notJson.status());
        assertEquals(404, current(a).status(), "ни один отказ профиль не создал");
    }

    @Test
    void sameIdempotencyKeyRepeatsTheFirstAnswer() throws IOException {
        Applicant a = new Applicant();
        String same = key();
        Response first = gateway.post(BASE, FORM, with(a.auth, "Idempotency-Key", same));
        assertEquals(201, first.status());
        Response repeat = gateway.post(BASE, FORM, with(a.auth, "Idempotency-Key", same));
        assertEquals(201, repeat.status());
        assertEquals("true", repeat.header("Idempotency-Replayed"));
        assertEquals(first.json(), repeat.json(), "тело хранится как jsonb: порядок полей может измениться, содержимое нет");
        assertEquals(first.header("ETag"), repeat.header("ETag"));

        Response otherKey = gateway.post(BASE, FORM, with(a.auth, "Idempotency-Key", key()));
        assertEquals(409, otherKey.status(), "другой ключ это новая попытка, а профиль уже есть");

        Response changedBody = gateway.post(BASE, "{\"name\":\"Другая\"}", with(a.auth, "Idempotency-Key", same));
        assertEquals(422, changedBody.status());
        assertEquals("idempotency-key-reuse", code(changedBody));
    }

    @Test
    void nobodyElseSeesOrChangesMyApplication() throws IOException {
        Applicant owner = new Applicant();
        Applicant stranger = new Applicant();
        String documentId = draftWithDocument(owner);

        assertEquals(404, current(stranger).status(), "у чужого пользователя своя (пустая) заявка");
        assertEquals(404, link(stranger, documentId).status());
        assertEquals(404, gateway.delete(CURRENT + "/documents/" + documentId, stranger.auth).status());
        assertEquals(200, link(owner, documentId).status());
    }

    // ---------------------------------------------------------------- документы

    @Test
    void typeIsTakenFromContentNotFromDeclaredHeaderOrName() throws IOException {
        Applicant a = new Applicant();
        assertEquals(201, create(a, "{}").status());

        Response pdf = upload(a, "паспорт.pdf", "application/pdf", pdf(300));
        assertEquals(201, pdf.status(), pdf.body());
        assertEquals("application/pdf", pdf.json().get("contentType"));
        assertEquals("паспорт.pdf", pdf.json().get("fileName"));
        assertEquals(300, ((Number) pdf.json().get("sizeBytes")).intValue());
        assertTrue(pdf.header("Location").startsWith(CURRENT + "/documents/"));
        assertEquals((String) pdf.json().get("documentId"), pdf.header("Location").substring((CURRENT + "/documents/").length()));

        Response jpeg = upload(a, "фото.jpeg", "image/jpeg", jpeg(500));
        assertEquals("image/jpeg", jpeg.json().get("contentType"));

        Response png = upload(a, "скан.png", "image/png", png(700));
        assertEquals("image/png", png.json().get("contentType"));

        Response lying = upload(a, "лежит.pdf", "application/pdf", png(100));
        assertEquals(201, lying.status());
        assertEquals("image/png", lying.json().get("contentType"), "заявленный тип и расширение не важны");

        Response text = upload(a, "письмо.pdf", "application/pdf", "Это просто текст, а не PDF".getBytes(StandardCharsets.UTF_8));
        assertEquals(415, text.status());
        assertEquals("unsupported-media-type", code(text));
        Response script = upload(a, "run.png", "image/png", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        assertEquals(415, script.status());

        Response empty = upload(a, "пусто.pdf", "application/pdf", new byte[0]);
        assertEquals(422, empty.status());
        assertEquals(List.of("/file"), pointers(empty));

        assertEquals(4, documents(current(a)).size(), "отвергнутые файлы в заявку не попали");
    }

    @Test
    void sizeLimitIsTwoMegabytesExactly() throws IOException {
        Applicant a = new Applicant();
        assertEquals(201, create(a, "{}").status());

        Response exactly = upload(a, "ровно.pdf", "application/pdf", pdf((int) SellerDocument.MAX_SIZE_BYTES));
        assertEquals(201, exactly.status(), exactly.body());
        assertEquals(SellerDocument.MAX_SIZE_BYTES, ((Number) exactly.json().get("sizeBytes")).longValue());

        Response over = upload(a, "больше.pdf", "application/pdf", pdf((int) SellerDocument.MAX_SIZE_BYTES + 1));
        assertEquals(413, over.status());
        assertEquals("payload-too-large", code(over));
        assertEquals(1, documents(current(a)).size());
    }

    @Test
    void eleventhDocumentIsRefusedAndDeletingFreesASlot() throws IOException {
        Applicant a = new Applicant();
        assertEquals(201, create(a, "{}").status());
        String first = null;
        for (int i = 1; i <= SellerDocument.MAX_PER_APPLICATION; i++) {
            Response ok = upload(a, "док-" + i + ".pdf", "application/pdf", pdf(100 + i));
            assertEquals(201, ok.status(), "документ " + i + ": " + ok.body());
            if (first == null) {
                first = (String) ok.json().get("documentId");
            }
        }
        Response eleventh = upload(a, "лишний.pdf", "application/pdf", pdf(200));
        assertEquals(422, eleventh.status());
        assertEquals("validation-failed", code(eleventh));
        assertEquals(List.of("/documents"), pointers(eleventh));
        assertEquals("out_of_range", ((Map<?, ?>) ((List<?>) eleventh.json().get("errors")).get(0)).get("code"));
        assertEquals(10, documents(current(a)).size());

        assertEquals(204, gateway.delete(CURRENT + "/documents/" + first, a.auth).status());
        assertEquals(404, gateway.delete(CURRENT + "/documents/" + first, a.auth).status(), "повтор после успеха");
        assertEquals(201, upload(a, "замена.pdf", "application/pdf", pdf(300)).status());
        assertEquals(10, documents(current(a)).size());
    }

    @Test
    void repeatedUploadWithTheSameKeyAddsOneDocument() throws IOException {
        Applicant a = new Applicant();
        assertEquals(201, create(a, "{}").status());
        String same = key();
        Response first = upload(a, same, "один.pdf", "application/pdf", pdf(400));
        Response repeat = upload(a, same, "один.pdf", "application/pdf", pdf(400));
        assertEquals(201, first.status());
        assertEquals(201, repeat.status());
        assertEquals("true", repeat.header("Idempotency-Replayed"));
        assertEquals(first.json().get("documentId"), repeat.json().get("documentId"));
        assertEquals(1, documents(current(a)).size());
    }

    @Test
    void uploadNeedsAMultipartFormWithOneFilePart() throws IOException {
        Applicant a = new Applicant();
        assertEquals(201, create(a, "{}").status());
        Response json = gateway.post(CURRENT + "/documents", "{}", with(a.auth, "Idempotency-Key", key()));
        assertEquals(415, json.status());

        String boundary = "----dgmform";
        byte[] noFile = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nтекст\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        Response extra = gateway.request("POST", CURRENT + "/documents", "multipart/form-data; boundary=" + boundary, noFile,
                with(a.auth, "Idempotency-Key", key()));
        assertEquals(422, extra.status());
        assertEquals(List.of("/note"), pointers(extra));

        byte[] broken = "--то не форма".getBytes(StandardCharsets.UTF_8);
        Response garbage = gateway.request("POST", CURRENT + "/documents", "multipart/form-data; boundary=" + boundary, broken,
                with(a.auth, "Idempotency-Key", key()));
        assertEquals(400, garbage.status());
        assertEquals("bad-request", code(garbage));
    }

    @Test
    void documentsAreOnlyAddedWhileTheApplicationIsEditable() throws IOException {
        Applicant a = new Applicant();
        String documentId = draftWithDocument(a);
        assertEquals(200, submit(a).status());

        Response late = upload(a, "позже.pdf", "application/pdf", pdf(100));
        assertEquals(409, late.status());
        assertEquals("on_review", late.json().get("currentStatus"));
        Response removal = gateway.delete(CURRENT + "/documents/" + documentId, a.auth);
        assertEquals(409, removal.status(), "на проверке документы не меняют");
        assertEquals(200, link(a, documentId).status(), "а смотреть можно");
    }

    // ---------------------------------------------------------------- ссылка на документ

    @Test
    void documentLinkOpensTheStoredFileAndOnlyInTheFiveMinuteWindow() throws Exception {
        Applicant a = new Applicant();
        assertEquals(201, create(a, FORM).status());
        byte[] original = pdf(5000);
        Response uploaded = upload(a, "справка.pdf", "application/pdf", original);
        String documentId = (String) uploaded.json().get("documentId");
        String sellerId = sellerId(current(a));

        String key = db.queryForObject("select object_key from seller_onboarding.seller_document where id = ?::uuid", String.class, documentId);
        assertEquals(SellerDocument.objectKey(UUID.fromString(sellerId), UUID.fromString(documentId)), key);
        assertFalse(key.contains("://") || key.contains("справка"), "в базе только ключ объекта, без адреса и имени файла");

        Response issued = link(a, documentId);
        assertEquals(200, issued.status(), issued.body());
        assertEquals("no-store", issued.header("Cache-Control"));
        String url = (String) issued.json().get("url");
        assertTrue(url.startsWith("https://localhost:8443/files/seller-documents/"), url);
        long left = Duration.between(Instant.now(), instant(issued, "expiresAt")).toSeconds();
        assertTrue(left > 270 && left <= 300, "ссылка живёт 5 минут, осталось " + left + " с");

        Fetched file = fetch(url);
        assertEquals(200, file.status, "ссылка открывается без токена");
        assertArrayEquals(original, file.body);
        assertEquals("application/pdf", file.header("content-type"));

        assertEquals(200, fetch(storageAt(Duration.ofSeconds(-290)).presignGet(key, Duration.ofSeconds(300)).url().toString()).status,
                "на 290-й секунде ссылка ещё действует");
        Fetched expired = fetch(storageAt(Duration.ofSeconds(-301)).presignGet(key, Duration.ofSeconds(300)).url().toString());
        assertTrue(expired.status >= 400 && expired.status < 500, "на 301-й секунде ссылка не работает, ответ " + expired.status);
        assertFalse(java.util.Arrays.equals(original, expired.body));

        String tampered = url.replace("seller-documents/", "seller-documents/x");
        assertTrue(fetch(tampered).status >= 400, "изменённый адрес подпись не пройдёт");
        assertTrue(fetch(url, "evil.example").status >= 400, "ссылка действует только для имени узла, под которым подписана");
    }

    @Test
    void deletingADocumentRemovesTheFileFromStorage() throws Exception {
        Applicant a = new Applicant();
        String documentId = draftWithDocument(a);
        String url = (String) link(a, documentId).json().get("url");
        assertEquals(200, fetch(url).status);

        assertEquals(204, gateway.delete(CURRENT + "/documents/" + documentId, a.auth).status());
        assertEquals(404, link(a, documentId).status());
        Fetched gone = fetch(url);
        assertEquals(404, gone.status, "объекта в хранилище больше нет");
        assertEquals(0L, db.queryForObject("select count(*) from seller_onboarding.seller_document where id = ?::uuid", Long.class, documentId));
    }

    // ---------------------------------------------------------------- отказ, пауза, доработка

    @Test
    void afterRejectionNewApplicationWaitsOutThePause() throws IOException {
        Applicant a = new Applicant();
        draftWithDocument(a);
        Response submitted = submit(a);
        assertEquals(200, submitted.status());
        String sellerId = sellerId(submitted);

        Instant rejectedAt = Instant.now();
        db.update("update seller_onboarding.seller_profile set status = 'rejected', decision_reason = ?, last_rejected_at = ? where id = ?::uuid",
                "Документы не читаются", utc(rejectedAt), sellerId);

        Response seen = current(a);
        assertEquals("rejected", seen.json().get("status"));
        assertEquals("Документы не читаются", seen.json().get("decisionReason"));
        assertTrue(Math.abs(rejectedAt.plus(PAUSE).toEpochMilli() - instant(seen, "nextApplicationAt").toEpochMilli()) <= 1,
                "nextApplicationAt = отказ + 24 часа: " + seen.json().get("nextApplicationAt"));

        Response early = submit(a);
        assertEquals(409, early.status());
        assertEquals("application-pause-active", code(early));
        assertNotNull(early.json().get("retryAfterAt"));
        long retryAfter = Long.parseLong(early.header("Retry-After"));
        assertTrue(retryAfter > 86_300 && retryAfter <= 86_400, "Retry-After " + retryAfter);

        // Правка сразу после отказа разрешена: она возвращает профиль в черновик (T6), но отправить его до конца паузы нельзя
        Response reopened = replace(a, seen.header("ETag"), FORM);
        assertEquals(200, reopened.status(), reopened.body());
        assertEquals("draft", reopened.json().get("status"));
        assertEquals("Документы не читаются", reopened.json().get("decisionReason"), "причина отказа остаётся на виду");
        Response stillWaiting = submit(a);
        assertEquals(409, stillWaiting.status());
        assertEquals("application-pause-active", code(stillWaiting));

        // Граница: за 5 секунд до конца паузы нельзя, через 5 секунд после конца можно
        db.update("update seller_onboarding.seller_profile set last_rejected_at = ? where id = ?::uuid",
                utc(Instant.now().minus(PAUSE).plusSeconds(5)), sellerId);
        assertEquals("application-pause-active", code(submit(a)));
        db.update("update seller_onboarding.seller_profile set last_rejected_at = ? where id = ?::uuid",
                utc(Instant.now().minus(PAUSE).minusSeconds(5)), sellerId);
        Response again = submit(a);
        assertEquals(200, again.status(), again.body());
        assertEquals("on_review", again.json().get("status"));
        assertEquals(REVIEW, Duration.between(instant(again, "submittedAt"), instant(again, "reviewDeadlineAt")), "срок считается заново");
        assertNull(again.json().get("decisionReason"));
    }

    @Test
    void rejectedApplicationCanNotBeSubmittedWithoutEditing() throws IOException {
        Applicant a = new Applicant();
        draftWithDocument(a);
        String sellerId = sellerId(submit(a));
        db.update("update seller_onboarding.seller_profile set status = 'rejected', decision_reason = 'Причина', last_rejected_at = ? where id = ?::uuid",
                utc(Instant.now().minus(PAUSE).minusSeconds(60)), sellerId);
        Response response = submit(a);
        assertEquals(409, response.status(), "пауза прошла, но заявку сначала надо исправить");
        assertEquals("state-conflict", code(response));
        assertEquals("rejected", response.json().get("currentStatus"));
    }

    @Test
    void returnedApplicationIsResubmittedWithoutPause() throws IOException {
        Applicant a = new Applicant();
        draftWithDocument(a);
        String sellerId = sellerId(submit(a));
        db.update("update seller_onboarding.seller_profile set status = 'returned', decision_reason = 'Уточните реквизиты' where id = ?::uuid", sellerId);

        Response returned = current(a);
        assertEquals("returned", returned.json().get("status"));
        assertEquals("Уточните реквизиты", returned.json().get("decisionReason"));
        assertNull(returned.json().get("nextApplicationAt"), "паузы после доработки нет");

        Response incomplete = replace(a, returned.header("ETag"), "{\"name\":\"Только имя\"}");
        assertEquals(422, incomplete.status(), "на доработке анкета должна оставаться полной");

        Response fixed = replace(a, returned.header("ETag"), FORM.replace("044525000", "044525225"));
        assertEquals(200, fixed.status(), fixed.body());
        assertEquals("returned", fixed.json().get("status"));
        Response resubmitted = submit(a);
        assertEquals(200, resubmitted.status(), resubmitted.body());
        assertEquals("on_review", resubmitted.json().get("status"));
        assertNull(resubmitted.json().get("decisionReason"));
    }

    // ---------------------------------------------------------------- срок рассмотрения

    @Test
    void overdueApplicationIsMarkedOnceByTheJob() throws IOException {
        Applicant a = new Applicant();
        draftWithDocument(a);
        String sellerId = sellerId(submit(a));
        Instant submittedAt = Instant.now().minus(Duration.ofDays(4));
        Instant deadline = submittedAt.plus(REVIEW);

        assertNull(markedAt(sellerId));
        db.update("update seller_onboarding.seller_profile set submitted_at = ?, review_deadline_at = ? where id = ?::uuid",
                utc(submittedAt), utc(deadline), sellerId);
        assertTrue(deadlineJob.runPass() >= 1);
        Object marked = markedAt(sellerId);
        assertNotNull(marked, "просрочка помечена");
        assertEquals(0, deadlineJob.runPass(), "второй проход ничего не помечает");
        assertEquals(marked, markedAt(sellerId), "метка ставится один раз и не сдвигается");

        Response seen = current(a);
        assertEquals("on_review", seen.json().get("status"), "автоматического решения нет (US-8.8)");
    }

    @Test
    void applicationInsideItsDeadlineIsNotMarked() throws IOException {
        Applicant a = new Applicant();
        draftWithDocument(a);
        String sellerId = sellerId(submit(a));
        deadlineJob.runPass();
        assertNull(markedAt(sellerId));
    }

    private static Object markedAt(String sellerId) {
        return db.queryForObject("select overdue_marked_at from seller_onboarding.seller_profile where id = ?::uuid", Object.class, sellerId);
    }

    // ---------------------------------------------------------------- права доступа (ST-02)

    private record Operation(String name, String method, String path) {
    }

    private static final List<Operation> OPERATIONS = List.of(
            new Operation("createSellerApplication", "POST", BASE),
            new Operation("getCurrentSellerApplication", "GET", CURRENT),
            new Operation("replaceSellerApplicationDraft", "PUT", CURRENT),
            new Operation("submitSellerApplication", "POST", CURRENT + "/submit"),
            new Operation("uploadSellerDocument", "POST", CURRENT + "/documents"),
            new Operation("deleteSellerDocument", "DELETE", CURRENT + "/documents/0199e0a0-0000-7000-8000-0000000000aa"),
            new Operation("getSellerDocumentLink", "GET", CURRENT + "/documents/0199e0a0-0000-7000-8000-0000000000aa/link"));

    private static Response call(Operation operation, String[] auth) throws IOException {
        String[] headers = auth;
        if (operation.method.equals("POST")) {
            headers = with(auth, "Idempotency-Key", key());
        } else if (operation.method.equals("PUT")) {
            headers = with(auth, "If-Match", "\"1\"");
        }
        return switch (operation.method) {
            case "GET" -> gateway.get(operation.path, headers);
            case "DELETE" -> gateway.delete(operation.path, headers);
            case "PUT" -> gateway.put(operation.path, "{}", headers);
            default -> gateway.post(operation.path, operation.path.endsWith("/documents") ? null : "{}", headers);
        };
    }

    @Test
    void everyOperationRefusesCallersWithoutTheRight() throws IOException {
        String noScope = TOKENS.token().scopes("orders.read").roles("buyer").amr("pwd").build();
        String staff = TOKENS.token().scopes("seller.apply").roles("moderator").amr("pwd", "otp").build();
        String smsOnly = TOKENS.token().scopes("seller.apply").roles("buyer").amr("sms").build();
        for (Operation operation : OPERATIONS) {
            Response anonymous = call(operation, new String[0]);
            assertEquals(401, anonymous.status(), operation.name + ": без токена");
            assertEquals("unauthenticated", code(anonymous), operation.name);

            Response wrongScope = call(operation, StandHttp.bearer(noScope));
            assertEquals(403, wrongScope.status(), operation.name + ": чужая область");
            assertEquals("forbidden", code(wrongScope), operation.name);

            Response wrongRole = call(operation, StandHttp.bearer(staff));
            assertEquals(403, wrongRole.status(), operation.name + ": роль модератора");
            assertEquals("forbidden", code(wrongRole), operation.name);

            Response sms = call(operation, StandHttp.bearer(smsOnly));
            assertEquals(403, sms.status(), operation.name + ": сессия по SMS");
            assertEquals("full-login-required", code(sms), operation.name);
        }
    }

    // ---------------------------------------------------------------- хранилище напрямую

    /** Клиент хранилища с часами, сдвинутыми от настоящих: так проверяется конец срока ссылки без ожидания. */
    private static ObjectStorage storageAt(Duration shift) {
        S3Settings settings = new S3Settings(URI.create(Stand.storageEndpoint()), "us-east-1", "seller-documents", "catalog-service",
                SecretFiles.read("storage_catalog"), URI.create("https://localhost:8443/files"), Duration.ofSeconds(10));
        SSLContext tls = TlsMaterial.forService(Stand.secrets(), "catalog-service").trustOnlyContext();
        return new S3ObjectStorage(settings, tls, Clock.offset(Clock.systemUTC(), shift));
    }

    /** Ответ хранилища на запрос по ссылке. */
    private record Fetched(int status, Map<String, String> headers, byte[] body) {

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static Fetched fetch(String url) throws IOException {
        return fetch(url, URI.create(url).getAuthority());
    }

    /**
     * Открыть ссылку так, как это сделает шлюз: на хранилище уходит адрес без префикса {@code /files}, а Host остаётся прежним
     * (подпись считается для имени шлюза). Встроенный клиент JDK заголовок Host менять не даёт, поэтому запрос пишется в сокет TLS.
     */
    private static Fetched fetch(String url, String host) throws IOException {
        URI link = URI.create(url);
        String path = link.getRawPath();
        assertTrue(path.startsWith("/files/"), path);
        String target = path.substring("/files".length()) + (link.getRawQuery() == null ? "" : "?" + link.getRawQuery());
        URI storage = URI.create(Stand.storageEndpoint());
        SSLContext tls = TlsMaterial.forService(Stand.secrets(), "api-gateway").trustOnlyContext();
        try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(storage.getHost(), storage.getPort())) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return parse(socket.getInputStream().readAllBytes());
        }
    }

    private static Fetched parse(byte[] raw) {
        int end = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
        assertTrue(end > 0, "ответ хранилища без заголовков");
        String[] lines = new String(raw, 0, end, StandardCharsets.ISO_8859_1).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        byte[] rest = java.util.Arrays.copyOfRange(raw, end + 4, raw.length);
        boolean chunked = "chunked".equalsIgnoreCase(headers.get("transfer-encoding"));
        return new Fetched(status, headers, chunked ? unchunk(rest) : rest);
    }

    private static byte[] unchunk(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < data.length) {
            int lineEnd = indexOf(data, "\r\n".getBytes(StandardCharsets.US_ASCII), pos);
            int size = Integer.parseInt(new String(data, pos, lineEnd - pos, StandardCharsets.US_ASCII).split(";")[0].trim(), 16);
            if (size == 0) {
                break;
            }
            out.write(data, lineEnd + 2, size);
            pos = lineEnd + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int k = 0; k < pattern.length; k++) {
                if (data[i + k] != pattern[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
