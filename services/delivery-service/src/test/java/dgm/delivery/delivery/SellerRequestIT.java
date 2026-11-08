package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.domain.SellerCallResult;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Выдача по запросу к системе продавца на стенде (ADR-028, SM-06, Р-190, Р-191): настоящие PostgreSQL, Kafka (событие {@code order.paid} с
 * {@code issuanceMethod = seller_api}), почтовая заглушка и сам сервис с его контроллером {@code respondSellerDelivery}, вызываемым как шлюз
 * (сертификат {@code api-gateway}, личность по ключу в заголовках). Система продавца, каталог подключений и сервис остатков подставлены:
 * {@link SellerSystem}, {@link InventoryStub}. Проверяются создание запроса и то, что диспетчер не берёт выдачу до ответа, отправка с повторами, ответ
 * с ключами и письмо, тайм-аут, отказ и ошибка продавца, повторы и чужие вызовы ответа, неверное тело. Гонку ответа и тайм-аута проверяет
 * {@link SellerAnswerConcurrencyIT}. Сеть, подпись и защиту от SSRF проверяют модульные тесты и сценарий К15 на стенде.
 */
class SellerRequestIT extends DeliveryStandIT {

    /** Заказ по API: оплачен, у продавца есть подключение. */
    record ApiOrder(Placed order, UUID seller, UUID product) {
        UUID id() {
            return order.orderId();
        }
    }

    // ------------------------------------------------------------------------------------------------ заказы и запросы

    /** Оплаченный заказ по API приходит событием; метод ждёт, пока потребитель создаст выдачу и запрос. */
    static ApiOrder placeApi(int quantity, Instant paidAt) throws Exception {
        UUID seller = UUID.randomUUID();
        SELLER.connect(seller);
        Placed p = newOrder(quantity);
        Map<String, Object> data = paidData(p, paidAt);
        data.put("sellerId", seller.toString());
        data.put("issuanceMethod", "seller_api");
        publish(orderEvents, "order-service", "order.events", "Order", "order.paid", UUID.randomUUID(), p.orderId().toString(), data);
        assertTrue(await(LIMIT, () -> deliveryOf(p) != null && requestOf(p.orderId()) != null), "выдача и запрос заказа " + p.orderId() + " не созданы по order.paid");
        INVENTORY.expectSellerKeys(p.orderId(), quantity);
        return new ApiOrder(p, seller, UUID.fromString((String) data.get("productId")));
    }

    static ApiOrder placeApi(int quantity) throws Exception {
        return placeApi(quantity, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** Заказ, оплаченный давно: окно ответа продавца уже вышло, запрос получает срок через секунду после создания. */
    static ApiOrder placeApiPaidLongAgo(int quantity) throws Exception {
        return placeApi(quantity, Instant.now().minus(20, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS));
    }

    /** Запрос продавцу по заказу или {@code null}. */
    static Map<String, Object> requestOf(UUID orderId) {
        List<Map<String, Object>> rows = db.queryForList("select r.delivery_id, r.request_id, r.seller_id, r.product_id, r.order_number, r.status, r.attempts, "
                + "(r.next_attempt_at is not null) as scheduled, extract(epoch from r.next_attempt_at - now()) as next_in, (r.lease_until is not null) as leased, "
                + "extract(epoch from r.deadline_at - r.created_at) as window_s, r.error_code, r.error_message, (r.accepted_at is not null) as has_accepted_at, "
                + "(r.answered_at is not null) as has_answered_at from delivery.seller_request r join delivery.delivery d on d.id = r.delivery_id "
                + "where d.order_id = ? and d.kind = 'primary'", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static Map<String, Object> requestOf(ApiOrder o) {
        return requestOf(o.id());
    }

    static UUID requestId(ApiOrder o) {
        return (UUID) requestOf(o).get("request_id");
    }

    static String requestStatus(ApiOrder o) {
        return (String) requestOf(o).get("status");
    }

    static int attemptsOf(ApiOrder o) {
        return ((Number) requestOf(o).get("attempts")).intValue();
    }

    /** Следующая отправка «наступила»: тест не ждёт настоящих пауз 5 и 30 секунд. */
    static void makeDue(ApiOrder o) {
        assertEquals(1, db.update("update delivery.seller_request set next_attempt_at = now() - interval '1 second' where request_id = ? and next_attempt_at is not null",
                requestId(o)));
    }

    // ------------------------------------------------------------------------------------------------ вызов ответа

    static Response answer(UUID seller, UUID requestId, String json) throws IOException {
        return gateway.post("/api/v1/seller-api/delivery-requests/" + requestId + "/answer", json, "X-Seller-Id", seller.toString(), "X-Auth-Kind", "api-key");
    }

    static Response answerKeys(ApiOrder o, List<String> keys) throws IOException {
        return answer(o.seller(), requestId(o), Json.write(Map.of("keys", keys)));
    }

    /** Ключи, которые продавец пришлёт: уникальные значения, число равно заказанному. */
    static List<String> sellerKeys(int count) {
        String prefix = "SK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            keys.add(prefix + "-" + i);
        }
        return keys;
    }

    /** Код проблемы из поля {@code type} ответа RFC 9457. */
    static String problem(Response response) {
        String type = (String) response.json().get("type");
        return type == null ? "" : type.substring(type.lastIndexOf('/') + 1);
    }

    static int eventsOfOrder(ApiOrder o, String type) {
        return events(o.order(), type).size();
    }

    // ------------------------------------------------------------------------------------------------ TC-963: создание и отправка запроса

    @Tag("TC-963")
    @Test
    void aPaidOrderByApiGetsOneRequestWithTheWindowOfTheSellerAndTheDispatcherLeavesTheDeliveryUntilTheAnswer() throws Exception {
        ApiOrder o = placeApi(2);

        Map<String, Object> request = requestOf(o);
        assertEquals("requested", request.get("status"));
        assertEquals(0, ((Number) request.get("attempts")).intValue());
        assertEquals(o.seller(), request.get("seller_id"));
        assertEquals(o.product(), request.get("product_id"));
        assertEquals(1001L, ((Number) request.get("order_number")).longValue());
        long window = ((Number) request.get("window_s")).longValue();
        assertTrue(window >= 590 && window <= 600, "окно ответа продавца 600 секунд от оплаты, сейчас " + window);
        assertEquals("queued", status(o.order()));

        for (int i = 0; i < 3; i++) {
            dispatcher.runOnce();
            pause(200);
        }

        assertEquals("queued", status(o.order()), "диспетчер не берёт выдачу, пока запрос продавцу не получил ответ");
        assertEquals(0, attemptNo(o.order()), "письмо не отправлялось");
        assertEquals(0, mails(o.order()).size());
        assertEquals(0, INVENTORY.calls(o.id()).size(), "ключи у сервиса остатков не запрашивались");
        assertEquals("requested", requestStatus(o));
    }

    @Tag("TC-963")
    @Test
    void theRequestGoesToTheSellerOnceWithTheDataOfTheOrderAndTheAcceptanceIsRecorded() throws Exception {
        ApiOrder o = placeApi(2);

        assertEquals(1, requestJob.runOnce());

        List<SellerSystem.Sent> sent = SELLER.sent(o.id());
        assertEquals(1, sent.size());
        SellerSystem.Sent request = sent.get(0);
        assertEquals(requestId(o), request.requestId());
        assertEquals(o.id(), request.orderId());
        assertEquals(o.seller(), request.sellerId());
        assertEquals(o.product(), request.productId());
        assertEquals(1001L, request.orderNumber());
        assertEquals(2, request.quantity());
        assertEquals(0, request.attempts(), "это первая отправка");
        assertFalse(request.toString().contains(o.order().email()), "адреса покупателя в запросе продавцу нет");
        Map<String, Object> row = requestOf(o);
        assertEquals("accepted", row.get("status"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        assertEquals(false, row.get("scheduled"), "принятому запросу следующая отправка не назначена");
        assertEquals(true, row.get("has_accepted_at"));

        assertEquals(0, requestJob.runOnce(), "принятый запрос повторно не уходит");
        assertEquals(1, SELLER.sent(o.id()).size());
        assertEquals("queued", status(o.order()));
    }

    @Tag("TC-963")
    @Test
    void temporaryFailuresAreRepeatedAfterFiveAndThirtySecondsAndAfterTheThirdOneTheRequestWaitsForTheTimeout() throws Exception {
        ApiOrder o = placeApi(1);
        SELLER.then(new SellerCallResult.Retryable("http_500"), new SellerCallResult.Retryable("timeout"), new SellerCallResult.Retryable("io_error"));

        assertEquals(1, requestJob.runOnce());
        Map<String, Object> first = requestOf(o);
        assertEquals("requested", first.get("status"));
        assertEquals(1, ((Number) first.get("attempts")).intValue());
        double firstPause = ((Number) first.get("next_in")).doubleValue();
        assertTrue(firstPause > 3 && firstPause <= 5.5, "после первой неудачи пауза 5 секунд, сейчас " + firstPause);
        assertEquals(0, requestJob.runOnce(), "до срока повтор не уходит");

        makeDue(o);
        assertEquals(1, requestJob.runOnce());
        Map<String, Object> second = requestOf(o);
        assertEquals(2, ((Number) second.get("attempts")).intValue());
        double secondPause = ((Number) second.get("next_in")).doubleValue();
        assertTrue(secondPause > 28 && secondPause <= 30.5, "после второй неудачи пауза 30 секунд, сейчас " + secondPause);

        makeDue(o);
        assertEquals(1, requestJob.runOnce());
        Map<String, Object> third = requestOf(o);
        assertEquals("requested", third.get("status"));
        assertEquals(3, ((Number) third.get("attempts")).intValue());
        assertEquals(false, third.get("scheduled"), "после третьей неудачи отправок больше нет, запрос ждёт тайм-аут");
        assertEquals(3, SELLER.sent(o.id()).size());
        assertEquals(List.of(0, 1, 2), SELLER.sent(o.id()).stream().map(SellerSystem.Sent::attempts).toList(), "номера отправок идут подряд");
        assertEquals(0, requestJob.runOnce());
        assertEquals("queued", status(o.order()), "выдача остаётся в очереди: решает тайм-аут");
    }

    @Tag("TC-963")
    @Test
    void aTemporaryFailureFollowedByAnAcceptanceMakesTheRequestAccepted() throws Exception {
        ApiOrder o = placeApi(1);
        SELLER.then(new SellerCallResult.Retryable("http_503"));

        requestJob.runOnce();
        makeDue(o);
        requestJob.runOnce();

        assertEquals("accepted", requestStatus(o));
        assertEquals(2, attemptsOf(o));
        assertEquals(2, SELLER.sent(o.id()).size());
    }

    @Tag("TC-963")
    @Test
    void aRefusalOfTheSellerWithAClientErrorEndsTheRequestWithoutRepeatsAndTheDeliveryFailsWithTheSellerTimeoutEvent() throws Exception {
        ApiOrder o = placeApi(1);
        SELLER.then(new SellerCallResult.Rejected(400));

        assertEquals(1, requestJob.runOnce());

        Map<String, Object> row = requestOf(o);
        assertEquals("failed", row.get("status"));
        assertEquals("seller_rejected", row.get("error_code"));
        assertEquals(1, ((Number) row.get("attempts")).intValue());
        assertEquals(1, SELLER.sent(o.id()).size(), "отказ 4xx не повторяется");
        assertSellerFailureOnly(o, "seller_error", "seller_rejected");
    }

    // ------------------------------------------------------------------------------------------------ TC-964: ответ в срок

    @Tag("TC-964")
    @Test
    void anAnswerInTimeStoresTheKeysInTheKeyStoreAndTheLetterGoesToTheBuyerWithThem() throws Exception {
        ApiOrder o = placeApi(2);
        requestJob.runOnce();
        List<String> keys = sellerKeys(2);

        Response response = answerKeys(o, keys);

        assertEquals(200, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        Map<String, Object> body = response.json();
        assertEquals(requestId(o).toString(), body.get("requestId"));
        assertEquals("answered", body.get("status"));
        assertEquals(2, ((Number) body.get("stored")).intValue());
        assertFalse(response.body().contains(keys.get(0)), "значений ключей в ответе нет");

        List<InventoryStub.SellerKeysCall> stored = INVENTORY.sellerCalls(o.id());
        assertEquals(1, stored.size(), "ключи переданы в хранилище один раз");
        assertEquals(keys, stored.get(0).keys());
        assertEquals(requestId(o), stored.get(0).requestId());
        String idempotencyKey = stored.get(0).headers().get("idempotency-key");
        assertNotNull(idempotencyKey);
        assertTrue(idempotencyKey.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), "ключ идемпотентности UUID v4: " + idempotencyKey);

        Map<String, Object> row = requestOf(o);
        assertEquals("answered", row.get("status"));
        assertEquals(true, row.get("has_answered_at"));
        assertEquals(false, row.get("leased"), "аренда снята");

        assertTrue(drive(LIMIT, () -> "sent".equals(status(o.order()))), "выдача не стала «отправлено», сейчас " + status(o.order()));
        assertSent(o.order(), keys);
        assertEquals(1, events(o.order(), "delivery.accepted").size());
        assertEquals(0, eventsOfOrder(o, "delivery.seller-timeout"));
        assertEquals(0, eventsOfOrder(o, "delivery.failed"));
        for (String key : keys) {
            assertEquals(0, occurrences(key), "значение ключа продавца найдено в базе сервиса выдачи (INV-20)");
        }
    }

    @Tag("TC-964")
    @Test
    void anAnswerBeforeTheSellerAcknowledgedTheRequestIsAcceptedToo() throws Exception {
        ApiOrder o = placeApi(1);
        List<String> keys = sellerKeys(1);

        Response response = answerKeys(o, keys);

        assertEquals(200, response.status(), response.body());
        assertEquals("answered", requestStatus(o));
        assertEquals(0, requestJob.runOnce(), "запрос, получивший ответ, уже не отправляется");
        assertEquals(0, SELLER.sent(o.id()).size());
        assertTrue(drive(LIMIT, () -> "sent".equals(status(o.order()))));
        assertSent(o.order(), keys);
    }

    @Tag("TC-964")
    @Test
    void whenTheKeyStoreIsDownTheAnswerGetsAnUnavailableErrorAndTheRetryOfTheSellerSucceeds() throws Exception {
        ApiOrder o = placeApi(2);
        requestJob.runOnce();
        List<String> keys = sellerKeys(2);
        INVENTORY.modeIs(InventoryStub.Mode.DOWN);

        Response down = answerKeys(o, keys);

        assertEquals(503, down.status(), down.body());
        assertEquals("dependency-unavailable", problem(down));
        assertEquals("accepted", requestStatus(o), "запрос не закрыт");
        assertEquals(false, requestOf(o).get("leased"), "аренда снята, повтор возможен сразу");
        assertEquals("queued", status(o.order()));

        INVENTORY.modeIs(InventoryStub.Mode.OK);
        Response retry = answerKeys(o, keys);

        assertEquals(200, retry.status(), retry.body());
        assertEquals("answered", requestStatus(o));
        assertTrue(drive(LIMIT, () -> "sent".equals(status(o.order()))));
        assertSent(o.order(), keys);
    }

    // ------------------------------------------------------------------------------------------------ TC-965: молчание продавца

    @Tag("TC-965")
    @Test
    void aSilentSellerMakesTheTimeoutCloseTheRequestWithoutALetterAndWithOneSellerTimeoutEvent() throws Exception {
        ApiOrder o = placeApiPaidLongAgo(1);
        requestJob.runOnce();
        assertEquals("accepted", requestStatus(o));
        assertEquals(0, timeoutJob.runOnce(), "окно ещё не вышло: срок через секунду после создания запроса");

        pause(1300);
        assertEquals(1, timeoutJob.runOnce());

        assertEquals("timeout", requestStatus(o));
        Map<String, Object> delivery = deliveryOf(o.order());
        assertEquals("failed", delivery.get("status"));
        assertEquals("seller_unavailable", delivery.get("fail_reason"));
        assertEquals(false, delivery.get("scheduled"));
        List<Map<String, Object>> timeouts = events(o.order(), "delivery.seller-timeout");
        assertEquals(1, timeouts.size(), "событие тайм-аута в Outbox одно");
        Map<String, Object> data = data(timeouts.get(0));
        assertEquals(o.id().toString(), data.get("orderId"));
        assertEquals(o.seller().toString(), data.get("sellerId"));
        assertEquals("timeout", data.get("reason"));
        assertEquals(idOf(o.order()).toString(), data.get("deliveryId"));
        assertTrue(((Number) data.get("waitedSeconds")).longValue() >= 1);
        assertEquals(0, eventsOfOrder(o, "delivery.failed"), "delivery.failed не публикуется, заказ отменяет тайм-аут (Р-190)");
        assertEquals(0, eventsOfOrder(o, "delivery.accepted"));

        assertEquals(0, timeoutJob.runOnce(), "повторный проход ничего не закрывает");
        for (int i = 0; i < 3; i++) {
            dispatcher.runOnce();
            pause(200);
        }
        assertEquals(0, mails(o.order()).size(), "письма не было");
        assertEquals(0, INVENTORY.calls(o.id()).size());
        assertEquals(1, events(o.order(), "delivery.seller-timeout").size());
    }

    @Tag("TC-965")
    @Test
    void anAnswerAfterTheWindowIsRefusedWithoutStoringKeysAndTheTimeoutClosesTheRequest() throws Exception {
        ApiOrder late = placeApiPaidLongAgo(1);
        pause(1300);

        Response tooLate = answerKeys(late, sellerKeys(1));

        assertEquals(409, tooLate.status(), tooLate.body());
        assertEquals("request-expired", problem(tooLate));
        assertEquals(0, INVENTORY.sellerCalls(late.id()).size(), "после окна ключи в хранилище не передаются");
        assertEquals(1, timeoutJob.runOnce());
        assertEquals("timeout", requestStatus(late));
        assertEquals(1, events(late.order(), "delivery.seller-timeout").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-966: ошибка продавца и отключённое подключение

    @Tag("TC-966")
    @Test
    void anAnswerWithAnErrorClosesTheRequestAsASellerErrorAndTheOrderGetsOneSellerTimeoutEvent() throws Exception {
        ApiOrder o = placeApi(1);
        requestJob.runOnce();

        Response response = answer(o.seller(), requestId(o), "{\"error\": {\"code\": \"out_of_stock\", \"message\": \"Ключи закончились на нашей стороне\"}}");

        assertEquals(200, response.status(), response.body());
        assertEquals("failed", response.json().get("status"));
        assertNull(response.json().get("stored"));
        Map<String, Object> row = requestOf(o);
        assertEquals("failed", row.get("status"));
        assertEquals("out_of_stock", row.get("error_code"));
        assertEquals("Ключи закончились на нашей стороне", row.get("error_message"));
        assertSellerFailureOnly(o, "seller_error", "out_of_stock");
        assertFalse(events(o.order(), "delivery.seller-timeout").get(0).get("payload").toString().contains("Ключи закончились"),
                "пояснение продавца в событие не попадает");
        assertEquals(0, INVENTORY.sellerCalls(o.id()).size());
    }

    @Tag("TC-966")
    @Test
    void aDisabledConnectionAndAMissingConnectionFailTheRequestWithoutASendAndACatalogOutageSpendsNoAttempt() throws Exception {
        ApiOrder disabled = placeApi(1);
        SELLER.connect(disabled.seller(), "disabled");
        ApiOrder missing = placeApi(1);
        SELLER.forget(missing.seller());
        ApiOrder outage = placeApi(1);

        SELLER.catalogDown(true);
        requestJob.runOnce();
        assertEquals("requested", requestStatus(outage), "каталог недоступен: запрос не закрыт");
        assertEquals(0, attemptsOf(outage), "попытка не потрачена");
        SELLER.catalogDown(false);

        makeDue(outage);
        makeDue(disabled);
        makeDue(missing);
        requestJob.runOnce();

        assertEquals("failed", requestStatus(disabled));
        assertEquals("integration_disabled", requestOf(disabled).get("error_code"));
        assertEquals("failed", requestStatus(missing));
        assertEquals("integration_missing", requestOf(missing).get("error_code"));
        assertEquals(0, attemptsOf(disabled), "запрос не уходил");
        assertEquals(0, SELLER.sent(disabled.id()).size());
        assertEquals(0, SELLER.sent(missing.id()).size());
        assertSellerFailureOnly(disabled, "seller_error", "integration_disabled");
        assertSellerFailureOnly(missing, "seller_error", "integration_missing");
        assertEquals("accepted", requestStatus(outage), "после восстановления каталога запрос ушёл");
        assertEquals(1, SELLER.sent(outage.id()).size());
    }

    @Tag("TC-966")
    @Test
    void anAddressRejectedByTheGuardFailsTheRequestAndAnUnknownRefusalCodeIsStoredAsOther() throws Exception {
        ApiOrder rejected = placeApi(1);
        SELLER.then(new SellerCallResult.EndpointRejected());
        requestJob.runOnce();
        assertEquals("endpoint_rejected", requestOf(rejected).get("error_code"));
        assertSellerFailureOnly(rejected, "seller_error", "endpoint_rejected");

        ApiOrder odd = placeApi(1);
        Response response = answer(odd.seller(), requestId(odd), "{\"error\": {\"code\": \"brand_new_code\"}}");
        assertEquals(200, response.status(), response.body());
        assertEquals("other", requestOf(odd).get("error_code"));
        assertSellerFailureOnly(odd, "seller_error", "other");
    }

    /** Заказ закрыт по ошибке продавца: выдача «ошибка» без письма, ровно одно {@code delivery.seller-timeout} с причиной, без {@code delivery.failed}. */
    private static void assertSellerFailureOnly(ApiOrder o, String reason, String errorCode) {
        Map<String, Object> delivery = deliveryOf(o.order());
        assertEquals("failed", delivery.get("status"));
        assertEquals("seller_unavailable", delivery.get("fail_reason"));
        assertEquals(errorCode, delivery.get("last_error_code"));
        List<Map<String, Object>> timeouts = events(o.order(), "delivery.seller-timeout");
        assertEquals(1, timeouts.size());
        assertEquals(reason, data(timeouts.get(0)).get("reason"));
        assertEquals(0, events(o.order(), "delivery.failed").size());
        assertEquals(0, events(o.order(), "delivery.accepted").size());
        assertEquals(0, mails(o.order()).size());
    }

    // ------------------------------------------------------------------------------------------------ TC-967: повторы и чужие вызовы

    @Tag("TC-967")
    @Test
    void theSameKeysAgainGiveTheSameResultWithoutASecondStoreOfTheLetterAndOtherKeysAreRefused() throws Exception {
        ApiOrder o = placeApi(2);
        requestJob.runOnce();
        List<String> keys = sellerKeys(2);
        assertEquals(200, answerKeys(o, keys).status());

        Response again = answerKeys(o, keys);

        assertEquals(200, again.status(), again.body());
        assertEquals("answered", again.json().get("status"));
        assertEquals(2, ((Number) again.json().get("stored")).intValue());
        Response other = answerKeys(o, sellerKeys(2));
        assertEquals(409, other.status(), other.body());
        assertEquals("request-already-answered", problem(other));
        Response refusal = answer(o.seller(), requestId(o), "{\"error\": {\"code\": \"other\"}}");
        assertEquals(409, refusal.status(), refusal.body());
        assertEquals("request-already-answered", problem(refusal));

        assertTrue(drive(LIMIT, () -> "sent".equals(status(o.order()))));
        assertSent(o.order(), keys);
        assertEquals(1, mails(o.order()).size(), "письмо одно, сколько бы раз продавец ни повторил ответ");
        assertEquals("answered", requestStatus(o));
        assertEquals(0, eventsOfOrder(o, "delivery.seller-timeout"));
    }

    @Tag("TC-967")
    @Test
    void anAnswerAfterTheTimeoutIsRefusedAndAnAnswerToAClosedRequestAfterARefusalIsRefused() throws Exception {
        ApiOrder silent = placeApiPaidLongAgo(1);
        pause(1300);
        assertEquals(1, timeoutJob.runOnce());

        Response late = answerKeys(silent, sellerKeys(1));
        assertEquals(409, late.status(), late.body());
        assertEquals("request-expired", problem(late));
        assertEquals(0, INVENTORY.sellerCalls(silent.id()).size());

        ApiOrder refused = placeApi(1);
        assertEquals(200, answer(refused.seller(), requestId(refused), "{\"error\": {\"code\": \"cannot_issue\"}}").status());
        Response keysAfter = answerKeys(refused, sellerKeys(1));
        assertEquals(409, keysAfter.status(), keysAfter.body());
        assertEquals("request-already-answered", problem(keysAfter));
        Response again = answer(refused.seller(), requestId(refused), "{\"error\": {\"code\": \"cannot_issue\"}}");
        assertEquals(200, again.status(), "тот же отказ повторно: тот же итог");
        assertEquals("failed", again.json().get("status"));
        assertEquals(1, events(refused.order(), "delivery.seller-timeout").size(), "событие одно, сколько бы раз продавец ни повторил отказ");
    }

    @Tag("TC-967")
    @Test
    void aForeignSellerAndAnUnknownRequestAreTheSameNotFoundAndNothingIsStored() throws Exception {
        ApiOrder o = placeApi(1);
        requestJob.runOnce();

        Response foreign = answer(UUID.randomUUID(), requestId(o), Json.write(Map.of("keys", sellerKeys(1))));
        Response unknown = answer(o.seller(), UUID.randomUUID(), Json.write(Map.of("keys", sellerKeys(1))));
        Response notUuid = gateway.post("/api/v1/seller-api/delivery-requests/not-a-uuid/answer", Json.write(Map.of("keys", sellerKeys(1))), "X-Seller-Id",
                o.seller().toString(), "X-Auth-Kind", "api-key");

        for (Response response : List.of(foreign, unknown, notUuid)) {
            assertEquals(404, response.status(), response.body());
            assertEquals("not-found", problem(response));
        }
        assertEquals(foreign.json().get("title"), unknown.json().get("title"), "чужой запрос неотличим от несуществующего (U24)");
        assertEquals(foreign.json().get("detail"), unknown.json().get("detail"));
        assertEquals(0, INVENTORY.sellerCalls(o.id()).size());
        assertEquals("accepted", requestStatus(o), "чужой вызов запрос не меняет");
    }

    @Tag("TC-967")
    @Test
    void aCallWithoutTheIdentityOfAnApiKeyIsRefusedAndTheRequestIsNotTouched() throws Exception {
        ApiOrder o = placeApi(1);
        requestJob.runOnce();
        String body = Json.write(Map.of("keys", sellerKeys(1)));
        String path = "/api/v1/seller-api/delivery-requests/" + requestId(o) + "/answer";

        Response noHeaders = gateway.post(path, body);
        Response notAKey = gateway.post(path, body, "X-Seller-Id", o.seller().toString());

        assertEquals(401, noHeaders.status(), noHeaders.body());
        assertEquals(401, notAKey.status(), notAKey.body());
        assertEquals(0, INVENTORY.sellerCalls(o.id()).size());
        assertEquals("accepted", requestStatus(o));
    }

    // ------------------------------------------------------------------------------------------------ TC-969: проверка ответа

    @Tag("TC-969")
    @Test
    void aWrongNumberOfKeysAndADuplicateInTheKeyPoolAreRefusedWithTheirPositionAndTheRequestStaysOpen() throws Exception {
        ApiOrder o = placeApi(2);
        requestJob.runOnce();
        String one = Json.write(Map.of("keys", sellerKeys(1)));
        String three = Json.write(Map.of("keys", sellerKeys(3)));

        for (String body : List.of(one, three)) {
            Response response = answer(o.seller(), requestId(o), body);

            assertEquals(422, response.status(), response.body());
            assertEquals("validation-failed", problem(response));
            assertTrue(response.body().contains("\"/keys\""), "названа позиция: " + response.body());
            assertTrue(response.body().contains("out_of_range"), response.body());
        }
        assertEquals("accepted", requestStatus(o), "запрос открыт, продавец может исправить ответ");
        assertEquals(false, requestOf(o).get("leased"));

        String twice = "DUP-" + UUID.randomUUID().toString().substring(0, 8);
        Response duplicate = answer(o.seller(), requestId(o), Json.write(Map.of("keys", List.of(twice, twice))));
        assertEquals(422, duplicate.status(), duplicate.body());
        assertTrue(duplicate.body().contains("not_unique"), duplicate.body());
        assertFalse(duplicate.body().contains(twice), "значение ключа в ошибке не повторяется");
        assertEquals("accepted", requestStatus(o));
        assertEquals("queued", status(o.order()));

        List<String> good = sellerKeys(2);
        assertEquals(200, answerKeys(o, good).status());
        assertTrue(drive(LIMIT, () -> "sent".equals(status(o.order()))));
        assertSent(o.order(), good);
    }

    @Tag("TC-969")
    @Test
    void anAnswerWithExtraFieldsOrBothKindsOrNeitherIsRefusedBeforeAnythingIsStored() throws Exception {
        ApiOrder o = placeApi(1);
        requestJob.runOnce();
        String key = sellerKeys(1).get(0);
        List<String> bodies = List.of("{\"keys\": [\"" + key + "\"], \"note\": \"привет\"}", "{\"keys\": [\"" + key + "\"], \"error\": {\"code\": \"other\"}}", "{}",
                "{\"keys\": []}", "{\"keys\": [\"\"]}", "{\"keys\": [5]}", "{\"error\": {\"code\": \"Out Of Stock\"}}", "{\"error\": {\"code\": \"other\", \"extra\": 1}}",
                "{\"keys\": \"" + key + "\"}");

        for (String body : bodies) {
            Response response = answer(o.seller(), requestId(o), body);

            assertEquals(422, response.status(), body + " -> " + response.body());
            assertEquals("validation-failed", problem(response));
            assertFalse(response.body().contains(key), "значение ключа в ошибке не повторяется");
        }
        Response notJson = answer(o.seller(), requestId(o), "это не JSON");
        assertEquals(400, notJson.status(), notJson.body());
        assertEquals(0, INVENTORY.sellerCalls(o.id()).size(), "ничего не передано в хранилище ключей");
        assertEquals("accepted", requestStatus(o));
    }
}
