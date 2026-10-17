package dgm.catalog;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Товары продавца на стенде (шаг 12 фазы 4, SM-04, переходы T1, T2, T5, T6, T7, T11 – T13, US-3.1, US-3.2, US-3.6, US-3.8, US-4.1):
 * создание, правка с {@code If-Match}, отправка на модерацию, архив и восстановление, события {@code product.created} и
 * {@code product.updated} в Outbox вместе с изменением, чужой товар как несуществующий (ST-02), права по семи операциям. Сервис
 * работает в процессе теста, база и хранилище это набор {@code dev-kit} (общая основа {@link CatalogStandIT}).
 *
 * <p>Решения модератора (T3, T4, T8) это шаг 13: здесь товар переводится в «опубликован», «отклонён» и «заблокирован» прямым
 * изменением строки, чтобы проверить правила, которые действуют после них.
 */
class SellerProductIT extends CatalogStandIT {

    private static final String BASE = "/api/v1/seller/products";
    private static final String INPUT = "{\"title\":\"Лунная тропа: ключ Steam\",\"description\":\"Приключенческая игра, ключ активации Steam\","
            + "\"productType\":\"game_key\",\"platform\":\"Steam\",\"activationRegions\":[\"RU\",\"KZ\"],"
            + "\"price\":{\"amount\":149900,\"currency\":\"RUB\"},\"issuanceMethod\":\"key_pool\"}";

    private static StandHttp gateway;
    private static JdbcTemplate db;

    @BeforeAll
    static void clients() {
        gateway = StandHttp.asService("api-gateway", PORT);
        DataSource migrator = Stand.dataSource("migrator_catalog", "catalog_db");
        db = new JdbcTemplate(migrator);
    }

    // ---------------------------------------------------------------- помощники

    /** Одобренный продавец: профиль в статусе approved, токен с областью, ролью, вторым фактором и claim seller_id. */
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

    private static Response create(Seller seller, String input) throws IOException {
        return gateway.post(BASE, input, with(seller.auth, "Idempotency-Key", key()));
    }

    private static String createdId(Seller seller) throws IOException {
        Response created = create(seller, INPUT);
        assertEquals(201, created.status(), created.body());
        return (String) created.json().get("productId");
    }

    private static Response get(Seller seller, String productId) throws IOException {
        return gateway.get(BASE + "/" + productId, seller.auth);
    }

    private static Response put(Seller seller, String productId, String ifMatch, String input) throws IOException {
        return gateway.put(BASE + "/" + productId, input, ifMatch == null ? seller.auth : with(seller.auth, "If-Match", ifMatch));
    }

    private static Response action(Seller seller, String productId, String name) throws IOException {
        return gateway.post(BASE + "/" + productId + "/" + name, null, with(seller.auth, "Idempotency-Key", key()));
    }

    private static String code(Response response) {
        return (String) response.json().get("code");
    }

    /** Данные событий Outbox по товару: тип события и тело, в порядке записи. */
    private static List<Map<String, Object>> outbox(String productId, String eventType) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = ? order by id", String.class,
                productId, eventType)) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    private static long outboxCount(String productId) {
        return db.queryForObject("select count(*) from public.outbox where aggregate_id = ?", Long.class, productId);
    }

    private static Map<String, Object> row(String productId) {
        return db.queryForMap("select status, version, title, price, submitted_at, review_deadline_at, published_at, block_note from catalog.product "
                + "where id = ?::uuid", productId);
    }

    /** Решение модератора до шага 13: товар «опубликован» прямым изменением строки (путь draft, on_moderation, published проходит триггер). */
    private static void publish(String productId) {
        db.update("update catalog.product set status = 'on_moderation', submitted_at = now(), review_deadline_at = now() + interval '3 days', "
                + "version = version + 1 where id = ?::uuid", productId);
        db.update("update catalog.product set status = 'published', published_at = now(), submitted_at = null, review_deadline_at = null, "
                + "version = version + 1 where id = ?::uuid", productId);
    }

    private static String etagOf(Response response) {
        return response.header("ETag");
    }

    // ---------------------------------------------------------------- создание и чтение

    @Test
    void createStoresADraftAndWritesProductCreatedOnce() throws IOException {
        Seller seller = new Seller(true);
        Response created = create(seller, INPUT);

        assertEquals(201, created.status(), created.body());
        Map<String, Object> product = created.json();
        String productId = (String) product.get("productId");
        assertEquals("draft", product.get("status"));
        assertEquals(1, product.get("version"));
        assertEquals(seller.sellerId.toString(), product.get("sellerId"));
        assertEquals("\"1\"", etagOf(created));
        assertEquals(BASE + "/" + productId, created.header("Location"));
        assertEquals("no-store", created.header("Cache-Control"));

        Map<String, Object> stored = row(productId);
        assertEquals("draft", stored.get("status"));
        assertEquals(149_900L, stored.get("price"));

        List<Map<String, Object>> events = outbox(productId, "product.created");
        assertEquals(1, events.size());
        assertEquals(Map.of("productId", productId, "sellerId", seller.sellerId.toString(), "productType", "game_key", "issuanceMethod", "key_pool",
                "status", "draft", "version", 1), events.get(0));
        assertEquals(1, outboxCount(productId));
    }

    @Test
    void theSameIdempotencyKeyRepeatsTheAnswerAndCreatesNoSecondProduct() throws IOException {
        Seller seller = new Seller(true);
        String[] headers = with(seller.auth, "Idempotency-Key", key());
        Response first = gateway.post(BASE, INPUT, headers);
        Response second = gateway.post(BASE, INPUT, headers);

        assertEquals(201, first.status(), first.body());
        assertEquals(201, second.status(), second.body());
        assertEquals(first.json().get("productId"), second.json().get("productId"));
        assertEquals("true", second.header("Idempotency-Replayed"));
        assertEquals(1L, db.queryForObject("select count(*) from catalog.product where seller_id = ?", Long.class, seller.sellerId));
        assertEquals(1, outbox((String) first.json().get("productId"), "product.created").size());
    }

    @Test
    void getReturnsTheCardWithEtagAndListShowsTheProductFirst() throws IOException {
        Seller seller = new Seller(true);
        String older = createdId(seller);
        String newer = createdId(seller);

        Response card = get(seller, older);
        assertEquals(200, card.status(), card.body());
        assertEquals("\"1\"", etagOf(card));
        assertEquals(List.of("RU", "KZ"), card.json().get("activationRegions"));

        Response list = gateway.get(BASE, seller.auth);
        assertEquals(200, list.status(), list.body());
        List<?> items = (List<?>) list.json().get("items");
        assertEquals(newer, ((Map<?, ?>) items.get(0)).get("productId"));
        assertEquals(older, ((Map<?, ?>) items.get(1)).get("productId"));
        assertNull(((Map<?, ?>) list.json().get("page")).get("nextCursor"));
    }

    @Test
    void listPagesAndFiltersByStatusAndShowsOnlyOwnProducts() throws IOException {
        Seller seller = new Seller(true);
        Seller other = new Seller(true);
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mine.add(createdId(seller));
        }
        createdId(other);
        assertEquals(200, action(seller, mine.get(1), "archive").status());

        Response first = gateway.get(BASE + "?limit=2", seller.auth);
        assertEquals(200, first.status(), first.body());
        List<String> seen = new ArrayList<>();
        ((List<?>) first.json().get("items")).forEach(i -> seen.add((String) ((Map<?, ?>) i).get("productId")));
        String cursor = (String) ((Map<?, ?>) first.json().get("page")).get("nextCursor");
        assertNotNull(cursor);
        Response second = gateway.get(BASE + "?limit=2&cursor=" + cursor, seller.auth);
        ((List<?>) second.json().get("items")).forEach(i -> seen.add((String) ((Map<?, ?>) i).get("productId")));
        assertNull(((Map<?, ?>) second.json().get("page")).get("nextCursor"));
        List<String> expected = new ArrayList<>(mine);
        Collections.reverse(expected);
        assertEquals(expected, seen);

        Response drafts = gateway.get(BASE + "?status=draft,rejected", seller.auth);
        assertEquals(2, ((List<?>) drafts.json().get("items")).size());
        Response archived = gateway.get(BASE + "?status=archived", seller.auth);
        assertEquals(1, ((List<?>) archived.json().get("items")).size());
        assertEquals(422, gateway.get(BASE + "?status=draft,nope&limit=0", seller.auth).status());
    }

    // ---------------------------------------------------------------- проверки входа

    @Test
    void invalidInputIs422WithEveryFieldNamedAndNothingIsWritten() throws IOException {
        Seller seller = new Seller(true);
        long before = outboxCount(seller.sellerId.toString());
        Response empty = create(seller, "{}");
        assertEquals(422, empty.status(), empty.body());
        assertEquals("validation-failed", code(empty));
        assertEquals(7, ((List<?>) empty.json().get("errors")).size());

        assertEquals(422, create(seller, INPUT.replace("\"key_pool\"", "\"seller_api\"")).status());
        assertEquals(422, create(seller, INPUT.replace("149900", "0")).status());
        assertEquals(422, create(seller, INPUT.replace("\"RUB\"", "\"USD\"")).status());
        assertEquals(415, gateway.request("POST", BASE, "text/plain", INPUT.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                with(seller.auth, "Idempotency-Key", key())).status());
        assertEquals(400, gateway.post(BASE, "{", with(seller.auth, "Idempotency-Key", key())).status());
        assertEquals(0L, db.queryForObject("select count(*) from catalog.product where seller_id = ?", Long.class, seller.sellerId));
        assertEquals(before, outboxCount(seller.sellerId.toString()));
    }

    @Test
    void aSellerWhoIsNotApprovedOrHasNoProfileInTheTokenGets403() throws IOException {
        Seller notApproved = new Seller(false);
        Response refused = create(notApproved, INPUT);
        assertEquals(403, refused.status(), refused.body());
        assertEquals("forbidden", code(refused));
        assertEquals(403, gateway.get(BASE, notApproved.auth).status());
        assertEquals(0L, db.queryForObject("select count(*) from catalog.product where seller_id = ?", Long.class, notApproved.sellerId));

        String[] noClaim = StandHttp.bearer(TOKENS.token().subject(UUID.randomUUID().toString()).scopes("seller.catalog").roles("seller")
                .amr("pwd", "otp").build());
        Response none = gateway.post(BASE, INPUT, with(noClaim, "Idempotency-Key", key()));
        assertEquals(403, none.status(), none.body());
        assertEquals("forbidden", code(none));
    }

    // ---------------------------------------------------------------- правка и If-Match

    @Test
    void editSavesInDraftAndNeedsIfMatch() throws IOException {
        Seller seller = new Seller(true);
        String productId = createdId(seller);

        Response noHeader = put(seller, productId, null, INPUT);
        assertEquals(428, noHeader.status(), noHeader.body());
        assertEquals(400, put(seller, productId, "1", INPUT).status());

        Response saved = put(seller, productId, "\"1\"", INPUT.replace("149900", "99900").replace("Лунная тропа: ключ Steam", "Лунная тропа 2"));
        assertEquals(200, saved.status(), saved.body());
        assertEquals("\"2\"", etagOf(saved));
        assertEquals("draft", saved.json().get("status"));
        assertEquals("Лунная тропа 2", saved.json().get("title"));

        List<Map<String, Object>> updates = outbox(productId, "product.updated");
        assertEquals(1, updates.size());
        assertEquals(List.of("title", "price"), updates.get(0).get("changedFields"));
        assertEquals(2, updates.get(0).get("version"));
        assertEquals("draft", updates.get(0).get("status"));

        Response same = put(seller, productId, "\"2\"", INPUT.replace("149900", "99900").replace("Лунная тропа: ключ Steam", "Лунная тропа 2"));
        assertEquals(200, same.status());
        assertEquals("\"2\"", etagOf(same), "правка без отличий версию не меняет");
        assertEquals(1, outbox(productId, "product.updated").size(), "и события не пишет");
    }

    @Test
    void ofTwoParallelEditsWithTheSameVersionOneWinsAndTheOtherGets412() throws Exception {
        Seller seller = new Seller(true);
        String productId = createdId(seller);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Response>> calls = new ArrayList<>();
        for (int price : new int[] {111_00, 222_00}) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return put(seller, productId, "\"1\"", INPUT.replace("149900", Integer.toString(price)));
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Response> call : calls) {
            statuses.add(call.get(30, TimeUnit.SECONDS).status());
        }
        Collections.sort(statuses);

        assertEquals(List.of(200, 412), statuses);
        assertEquals(2, row(productId).get("version"));
        assertEquals(1, outbox(productId, "product.updated").size());
        Response stale = put(seller, productId, "\"1\"", INPUT.replace("149900", "333"));
        assertEquals(412, stale.status());
        assertEquals("version-conflict", code(stale));
    }

    @Test
    void editingThePublishedTitleReturnsTheProductToModerationButThePriceDoesNot() throws IOException {
        Seller seller = new Seller(true);
        String productId = createdId(seller);
        publish(productId);
        Response card = get(seller, productId);
        assertEquals("published", card.json().get("status"));

        Response price = put(seller, productId, etagOf(card), INPUT.replace("149900", "99900"));
        assertEquals(200, price.status(), price.body());
        assertEquals("published", price.json().get("status"), "T7: цена не возвращает на модерацию");
        assertNull(price.json().get("submittedAt"));
        Map<String, Object> priceEvent = outbox(productId, "product.updated").get(0);
        assertEquals(List.of("price"), priceEvent.get("changedFields"));
        assertEquals("published", priceEvent.get("status"));

        Response title = put(seller, productId, etagOf(price), INPUT.replace("149900", "99900").replace("Лунная тропа: ключ Steam", "Новое название"));
        assertEquals(200, title.status(), title.body());
        assertEquals("on_moderation", title.json().get("status"), "T6: название возвращает на модерацию");
        assertNotNull(title.json().get("submittedAt"));
        assertNotNull(title.json().get("reviewDeadlineAt"));
        Map<String, Object> titleEvent = outbox(productId, "product.updated").get(1);
        assertEquals(List.of("title", "status"), titleEvent.get("changedFields"));
        assertEquals("on_moderation", titleEvent.get("status"));

        Response frozen = put(seller, productId, etagOf(title), INPUT);
        assertEquals(409, frozen.status(), "на модерации править нельзя");
        assertEquals("on_moderation", frozen.json().get("currentStatus"));
    }

    @Test
    void aRejectedProductIsEditedAndResubmittedAndABlockedOneToo() throws IOException {
        Seller seller = new Seller(true);
        String rejected = createdId(seller);
        assertEquals(200, action(seller, rejected, "submit").status());
        db.update("update catalog.product set status = 'rejected', rejection_reason = 'Описание не соответствует товару', "
                + "submitted_at = null, review_deadline_at = null, version = version + 1 where id = ?::uuid", rejected);
        Response card = get(seller, rejected);
        assertEquals("Описание не соответствует товару", card.json().get("rejectionReason"));
        Response fixed = put(seller, rejected, etagOf(card), INPUT.replace("Приключенческая игра", "Приключенческая игра без изменений"));
        assertEquals("rejected", fixed.json().get("status"), "правка отклонённого статус не меняет: на модерацию его отправляет продавец");
        Response resubmitted = action(seller, rejected, "submit");
        assertEquals(200, resubmitted.status(), resubmitted.body());
        assertEquals("on_moderation", resubmitted.json().get("status"));
        assertNull(resubmitted.json().get("rejectionReason"));

        String blocked = createdId(seller);
        publish(blocked);
        db.update("update catalog.product set status = 'blocked', status_before_block = 'published', block_reason = 'moderator_decision', "
                + "block_note = 'Чужой товарный знак в названии', version = version + 1 where id = ?::uuid", blocked);
        Response blockedCard = get(seller, blocked);
        assertEquals("Чужой товарный знак в названии", blockedCard.json().get("blockReason"));
        assertEquals(409, action(seller, blocked, "archive").status(), "заблокированный товар в архив не уходит");
        Response edited = put(seller, blocked, etagOf(blockedCard), INPUT.replace("Лунная тропа: ключ Steam", "Лунная тропа"));
        assertEquals("blocked", edited.json().get("status"));
        Response back = action(seller, blocked, "submit");
        assertEquals(200, back.status(), back.body());
        assertEquals("on_moderation", back.json().get("status"), "T11");
        assertNull(back.json().get("blockReason"));
        Map<String, Object> stored = row(blocked);
        assertNull(stored.get("block_note"));
    }

    // ---------------------------------------------------------------- отправка, архив, восстановление

    @Test
    void submitArchiveAndRestoreFollowTheStatusMachineAndWriteOneEventEach() throws IOException {
        Seller seller = new Seller(true);
        String productId = createdId(seller);

        Response submitted = action(seller, productId, "submit");
        assertEquals(200, submitted.status(), submitted.body());
        assertEquals("on_moderation", submitted.json().get("status"));
        assertNotNull(submitted.json().get("submittedAt"));
        assertNotNull(submitted.json().get("reviewDeadlineAt"));
        Object deadline = submitted.json().get("reviewDeadlineAt");

        Response again = action(seller, productId, "submit");
        assertEquals(409, again.status(), again.body());
        assertEquals("state-conflict", code(again));
        assertEquals("on_moderation", again.json().get("currentStatus"));
        assertEquals(deadline, get(seller, productId).json().get("reviewDeadlineAt"), "повтор срок не сбросил");

        Response archived = action(seller, productId, "archive");
        assertEquals("archived", archived.json().get("status"));
        assertNull(archived.json().get("submittedAt"));
        assertEquals(409, action(seller, productId, "archive").status());
        assertEquals(409, put(seller, productId, etagOf(archived), INPUT).status(), "в архиве править нельзя");

        Response restored = action(seller, productId, "restore");
        assertEquals("draft", restored.json().get("status"));
        assertEquals("\"4\"", etagOf(restored));
        assertEquals(409, action(seller, productId, "restore").status());

        List<Map<String, Object>> updates = outbox(productId, "product.updated");
        assertEquals(List.of("on_moderation", "archived", "draft"), updates.stream().map(e -> e.get("status")).toList());
        assertEquals(List.of(2, 3, 4), updates.stream().map(e -> e.get("version")).toList());
        assertTrue(updates.stream().allMatch(e -> List.of("status").equals(e.get("changedFields"))));
    }

    @Test
    void theSameIdempotencyKeyOnSubmitRepeatsTheAnswerWithoutASecondEvent() throws IOException {
        Seller seller = new Seller(true);
        String productId = createdId(seller);
        String[] headers = with(seller.auth, "Idempotency-Key", key());
        Response first = gateway.post(BASE + "/" + productId + "/submit", null, headers);
        Response second = gateway.post(BASE + "/" + productId + "/submit", null, headers);

        assertEquals(200, first.status(), first.body());
        assertEquals(200, second.status(), second.body());
        assertEquals("true", second.header("Idempotency-Replayed"));
        assertEquals(first.json(), second.json());
        assertEquals(1, outbox(productId, "product.updated").size());
    }

    // ---------------------------------------------------------------- откат и чужой товар

    @Test
    void failureWhileWritingTheEventRollsBackTheEditAndTheStatusChange() throws IOException {
        Seller seller = new Seller(true);
        String productId = createdId(seller);
        db.execute("create or replace function public.it_refuse_update() returns trigger language plpgsql as $$ begin "
                + "raise exception 'отказ записи события для проверки отката'; end $$");
        db.execute("drop trigger if exists it_refuse_update on public.outbox");
        db.execute("create trigger it_refuse_update before insert on public.outbox for each row "
                + "when (new.event_type = 'product.updated' and new.aggregate_id = '" + productId + "') execute function public.it_refuse_update()");
        try {
            Response edit = put(seller, productId, "\"1\"", INPUT.replace("149900", "99900"));
            assertTrue(edit.status() >= 500, "сбой записи события это ошибка сервера: " + edit.status());
            Response submit = action(seller, productId, "submit");
            assertTrue(submit.status() >= 500, "и при отправке тоже: " + submit.status());

            Map<String, Object> stored = row(productId);
            assertEquals(1, stored.get("version"), "версия прежняя: строка не обновлялась");
            assertEquals("draft", stored.get("status"));
            assertEquals(149_900L, stored.get("price"));
            assertEquals(0, outbox(productId, "product.updated").size());
        } finally {
            db.execute("drop trigger if exists it_refuse_update on public.outbox");
            db.execute("drop function if exists public.it_refuse_update()");
        }

        Response retried = put(seller, productId, "\"1\"", INPUT.replace("149900", "99900"));
        assertEquals(200, retried.status(), "после устранения сбоя та же правка проходит: " + retried.body());
        assertEquals(1, outbox(productId, "product.updated").size());
    }

    @Test
    void aForeignProductIsIndistinguishableFromAMissingOne() throws IOException {
        Seller owner = new Seller(true);
        Seller stranger = new Seller(true);
        String productId = createdId(owner);
        String missing = UUID.randomUUID().toString();
        long events = outboxCount(productId);

        for (String id : List.of(productId, missing)) {
            Response read = get(stranger, id);
            assertEquals(404, read.status(), read.body());
            assertEquals("not-found", code(read));
            assertEquals(404, put(stranger, id, "\"1\"", INPUT).status());
            assertEquals(404, action(stranger, id, "submit").status());
            assertEquals(404, action(stranger, id, "archive").status());
            assertEquals(404, action(stranger, id, "restore").status());
        }
        Map<String, Object> stored = row(productId);
        assertEquals("draft", stored.get("status"));
        assertEquals(1, stored.get("version"));
        assertEquals(events, outboxCount(productId));
        assertEquals("draft", get(owner, productId).json().get("status"));
    }

    // ---------------------------------------------------------------- права доступа (ST-02)

    private record Operation(String name, String method, String path) {
    }

    private static final String SOME = "0199e0a0-0000-4000-8000-0000000000cc";
    private static final List<Operation> OPERATIONS = List.of(
            new Operation("createSellerProduct", "POST", BASE),
            new Operation("listSellerProducts", "GET", BASE),
            new Operation("getSellerProduct", "GET", BASE + "/" + SOME),
            new Operation("replaceSellerProduct", "PUT", BASE + "/" + SOME),
            new Operation("submitSellerProduct", "POST", BASE + "/" + SOME + "/submit"),
            new Operation("archiveSellerProduct", "POST", BASE + "/" + SOME + "/archive"),
            new Operation("restoreSellerProduct", "POST", BASE + "/" + SOME + "/restore"));

    private static Response call(Operation operation, String[] auth) throws IOException {
        return switch (operation.method) {
            case "GET" -> gateway.get(operation.path, auth);
            case "PUT" -> gateway.put(operation.path, INPUT, with(auth, "If-Match", "\"1\""));
            default -> gateway.post(operation.path, operation.path.equals(BASE) ? INPUT : null, with(auth, "Idempotency-Key", key()));
        };
    }

    @Test
    void everySellerOperationRefusesCallersWithoutTheRight() throws IOException {
        String sellerId = UUID.randomUUID().toString();
        String noScope = TOKENS.token().scopes("seller.apply").roles("seller").amr("pwd", "otp").sellerId(sellerId).build();
        String buyer = TOKENS.token().scopes("seller.catalog").roles("buyer").amr("pwd").build();
        String moderator = TOKENS.token().scopes("seller.catalog").roles("moderator").amr("pwd", "otp").build();
        String admin = TOKENS.token().scopes("seller.catalog").roles("admin").amr("pwd", "otp").build();
        String noSecondFactor = TOKENS.token().scopes("seller.catalog").roles("seller").amr("pwd").sellerId(sellerId).build();
        String smsOnly = TOKENS.token().scopes("seller.catalog").roles("seller").amr("sms").sellerId(sellerId).build();
        for (Operation operation : OPERATIONS) {
            Response anonymous = call(operation, new String[0]);
            assertEquals(401, anonymous.status(), operation.name + ": без токена");
            assertEquals("unauthenticated", code(anonymous), operation.name);

            assertEquals(403, call(operation, StandHttp.bearer(noScope)).status(), operation.name + ": чужая область");
            for (String wrongRole : List.of(buyer, moderator, admin)) {
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
        assertFalse(OPERATIONS.isEmpty());
    }
}
