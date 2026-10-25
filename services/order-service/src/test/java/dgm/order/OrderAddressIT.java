package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Смена адреса доставки по обращению (OP-72, SEQ-04, US-7.3) на стенде: вызывающий {@code platform-service} по mTLS, ключ идемпотентности охранника каркаса,
 * строка заказа в базе и событие {@code order.address-updated} в Outbox. Заказ «выдан» получает адрес, статус и сроки не сдвигаются; повтор с тем же ключом
 * отдаёт сохранённый ответ и ничего не пишет, другой ключ это новая повторная отправка; заказ в другом статусе получает 409 и не меняется. Адрес это
 * персональные данные: канарейка ищет его в журнале сервиса с повышенным уровнем, в метриках и в ответах об ошибках.
 *
 * <p>Заказ оформляется через API (соседи это {@link Neighbours}), статусы «оплачен» и «выдан» выставляются в базе под ролью миграций: путь оплаты и выдачи
 * проверяют тесты событий саги.
 */
class OrderAddressIT extends OrderStandIT {

    private static final UUID SELLER = UUID.randomUUID();
    private static final String INTERNAL = "/internal/v1/orders/";
    private static final String OPERATION = "/update-delivery-address";

    private static StandHttp platform;

    @BeforeAll
    static void connectPlatform() {
        platform = StandHttp.asService("platform-service", PORT);
    }

    // ------------------------------------------------------------------------------------------------ данные

    private static String canary() {
        return "canary-" + UUID.randomUUID() + "@mail.example";
    }

    /** Заказ «ожидает оплаты», оформленный через API: адрес снимка это почта из токена покупателя. */
    private static String placeAwaiting() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = NEIGHBOURS.product(SELLER, "Игра " + UUID.randomUUID().toString().substring(0, 8), 149_900L, 20);
        Response response = create(buyerToken(buyer), product, 1);
        assertEquals(201, response.status(), response.body());
        return (String) response.json().get("orderId");
    }

    private static String placeIssued() throws IOException {
        String orderId = placeAwaiting();
        UUID id = UUID.fromString(orderId);
        assertEquals(1, db.update("update orders.orders set status = 'paid', paid_at = now() where id = ?", id));
        assertEquals(1, db.update("update orders.orders set status = 'issued', issued_at = now() where id = ?", id));
        return orderId;
    }

    private static Response update(String orderId, String ticketId, String email, String idempotencyKey) throws IOException {
        return platform.post(INTERNAL + orderId + OPERATION, body(ticketId, email), "Idempotency-Key", idempotencyKey);
    }

    private static String body(String ticketId, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketId", ticketId);
        body.put("email", email);
        return Json.write(body);
    }

    private static String ticket() {
        return UUID.randomUUID().toString();
    }

    private static int version(String orderId) {
        return ((Number) order(orderId).get("version")).intValue();
    }

    private static String address(String orderId) {
        return (String) order(orderId).get("delivery_address");
    }

    private static List<Map<String, Object>> addressEvents(String orderId) {
        return events(orderId, "order.address-updated");
    }

    // ------------------------------------------------------------------------------------------------ TC-528

    @Tag("TC-528")
    @Test
    void anIssuedOrderGetsTheNewAddressAndAReplayWithTheSameKeyWritesNothing() throws IOException {
        String orderId = placeIssued();
        Map<String, Object> before = order(orderId);
        String ticket = ticket();
        String key = key();
        String address = canary();

        Response first = update(orderId, ticket, address, key);

        assertEquals(200, first.status(), first.body());
        assertEquals("no-store", first.header("Cache-Control"));
        assertNull(first.header("Idempotency-Replayed"));
        assertEquals(Set.of("orderId", "delivery", "updatedAt"), first.json().keySet());
        assertEquals(orderId, first.json().get("orderId"));
        assertEquals(Map.of("channel", "email", "address", address), first.json().get("delivery"));
        assertTrue(Instant.parse((String) first.json().get("updatedAt")).isBefore(Instant.now().plusSeconds(5)));

        Map<String, Object> after = order(orderId);
        assertEquals(address, after.get("delivery_address"));
        assertEquals("issued", after.get("status"));
        assertEquals(before.get("issued_at"), after.get("issued_at"), "окна 72 часа и 7 дней считаются от первой выдачи и не сдвигаются");
        assertEquals(before.get("paid_at"), after.get("paid_at"));
        assertEquals(before.get("created_at"), after.get("created_at"));
        assertEquals(before.get("number"), after.get("number"));
        assertEquals(before.get("amount"), after.get("amount"));
        assertEquals(((Number) before.get("version")).intValue() + 1, ((Number) after.get("version")).intValue());

        List<Map<String, Object>> events = addressEvents(orderId);
        assertEquals(1, events.size());
        assertEquals("order.events", events.get(0).get("topic"));
        assertEquals("Order", events.get(0).get("aggregate_type"));
        Map<String, Object> data = data(events.get(0));
        assertEquals(Set.of("orderId", "buyerId", "ticketId", "delivery"), data.keySet());
        assertEquals(orderId, data.get("orderId"));
        assertEquals(before.get("buyer_id").toString(), data.get("buyerId"));
        assertEquals(ticket, data.get("ticketId"));
        assertEquals(Map.of("channel", "email", "address", address), data.get("delivery"));

        Response replay = update(orderId, ticket, address, key);
        assertEquals(200, replay.status(), replay.body());
        assertEquals("true", replay.header("Idempotency-Replayed"));
        assertEquals(first.body(), replay.body());
        assertEquals(1, addressEvents(orderId).size(), "повтор с тем же ключом второго события не пишет");
        assertEquals(after.get("version"), order(orderId).get("version"), "и строку заказа не трогает");

        Response resend = update(orderId, ticket, address, key());
        assertEquals(200, resend.status(), resend.body());
        assertNull(resend.header("Idempotency-Replayed"));
        assertEquals(2, addressEvents(orderId).size(), "новый ключ это новая повторная отправка (так оператор повторяет её после неудачи)");
        assertEquals(((Number) after.get("version")).intValue() + 1, version(orderId));

        Response reuse = update(orderId, ticket, canary(), key);
        assertEquals(422, reuse.status(), reuse.body());
        assertEquals("idempotency-key-reuse", code(reuse));
        assertEquals(address, address(orderId));
        assertEquals(2, addressEvents(orderId).size());
    }

    @Tag("TC-528")
    @Test
    void theAddressIsWrittenAndPublishedEvenIfItDidNotChange() throws IOException {
        String orderId = placeIssued();
        String same = address(orderId);
        int version = version(orderId);

        Response response = update(orderId, ticket(), same, key());

        assertEquals(200, response.status(), response.body());
        assertEquals(same, address(orderId));
        assertEquals(version + 1, version(orderId), "SEQ-04: запись «адрес актуален на момент решения оператора» делается всегда");
        List<Map<String, Object>> events = addressEvents(orderId);
        assertEquals(1, events.size());
        assertEquals(Map.of("channel", "email", "address", same), data(events.get(0)).get("delivery"));
    }

    @Tag("TC-528")
    @Test
    void ordersThatAreNotIssuedGet409WithTheirStatusAndKeepTheAddress() throws IOException {
        String awaiting = placeAwaiting();
        String paid = placeAwaiting();
        assertEquals(1, db.update("update orders.orders set status = 'paid', paid_at = now() where id = ?", UUID.fromString(paid)));
        String cancelled = placeAwaiting();
        assertEquals(1, db.update("update orders.orders set status = 'cancelled', cancel_reason = 'payment_declined' where id = ?", UUID.fromString(cancelled)));
        String refunded = placeAwaiting();
        assertEquals(1, db.update("update orders.orders set status = 'cancelled', cancel_reason = 'reservation_expired' where id = ?", UUID.fromString(refunded)));
        assertEquals(1, db.update("update orders.orders set status = 'refunded' where id = ?", UUID.fromString(refunded)));
        Map<String, String> orders = new LinkedHashMap<>();
        orders.put(awaiting, "awaiting_payment");
        orders.put(paid, "paid");
        orders.put(cancelled, "cancelled");
        orders.put(refunded, "refunded");

        for (Map.Entry<String, String> entry : orders.entrySet()) {
            String orderId = entry.getKey();
            String address = canary();
            String original = address(orderId);
            int version = version(orderId);

            Response response = update(orderId, ticket(), address, key());

            assertEquals(409, response.status(), entry.getValue() + " " + response.body());
            assertEquals("state-conflict", code(response), entry.getValue());
            assertEquals(entry.getValue(), response.json().get("currentStatus"));
            assertFalse(response.body().contains(address), "адреса в ответе об отказе нет");
            assertEquals(original, address(orderId), entry.getValue());
            assertEquals(entry.getValue(), status(orderId));
            assertEquals(version, version(orderId), "заказ не тронут: " + entry.getValue());
            assertEquals(0, addressEvents(orderId).size(), entry.getValue());
        }
    }

    @Tag("TC-528")
    @Test
    void anUnknownOrderIs404() throws IOException {
        String address = canary();

        Response response = update(UUID.randomUUID().toString(), ticket(), address, key());

        assertEquals(404, response.status(), response.body());
        assertEquals("not-found", code(response));
        assertFalse(response.body().contains(address));
    }

    /**
     * Канарейка адреса: после успешной смены и отказов адрес ищется в журнале сервиса (TRACE у {@code dgm}, DEBUG у веб-слоя, безопасности и транзакций),
     * в метриках и в ответах об ошибках. Допустимые места адреса: строка заказа, событие в Outbox и ответ самой операции. Журнал {@code org.springframework.jdbc}
     * на TRACE не включается: он показывает значения параметров SQL любого запроса, это отладочный режим, которого в работе нет.
     */
    @Tag("TC-528")
    @Test
    void theAddressIsNotInTheJournalTheMetricsOrTheErrorAnswers() throws IOException {
        String issued = placeIssued();
        String paid = placeAwaiting();
        assertEquals(1, db.update("update orders.orders set status = 'paid', paid_at = now() where id = ?", UUID.fromString(paid)));
        String address = canary();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Map<String, Level> loud = new LinkedHashMap<>();
        loud.put("dgm", Level.TRACE);
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

        List<Response> failures = new ArrayList<>();
        Response success;
        try {
            success = update(issued, ticket(), address, key());
            failures.add(update(paid, ticket(), address, key()));
            failures.add(update(UUID.randomUUID().toString(), ticket(), address, key()));
            failures.add(platform.post(INTERNAL + issued + OPERATION, body(ticket(), "canary-not-an-address-" + UUID.randomUUID()), "Idempotency-Key", key()));
            failures.add(platform.post(INTERNAL + issued + OPERATION, "{\"ticketId\":\"x\",\"email\":\"" + address + "\",\"extra\":1}", "Idempotency-Key", key()));
            failures.add(platform.request("POST", INTERNAL + issued + OPERATION, "text/plain", address.getBytes(StandardCharsets.UTF_8),
                    "Idempotency-Key", key()));
            failures.add(platform.post(INTERNAL + "not-a-uuid" + OPERATION, body(ticket(), address), "Idempotency-Key", key()));
            failures.add(gateway.post(INTERNAL + issued + OPERATION, body(ticket(), address), "Idempotency-Key", key()));
        } finally {
            root.detachAppender(journal);
            journal.stop();
            for (Map.Entry<String, Level> entry : before.entrySet()) {
                context.getLogger(entry.getKey()).setLevel(entry.getValue());
            }
        }

        assertEquals(200, success.status(), success.body());
        assertTrue(success.body().contains(address), "контроль: ответ самой операции адрес несёт, поиск настоящий");
        assertEquals(address, address(issued));
        assertEquals(List.of(409, 404, 422, 422, 415, 400, 403), failures.stream().map(Response::status).toList(), failures.toString());
        for (Response failure : failures) {
            assertFalse(failure.body().contains(address), "в ответе об ошибке нашёлся адрес: " + failure.status() + " " + failure.body());
            assertFalse(failure.body().contains("canary-"), "в ответе об ошибке нашёлся образец: " + failure.status() + " " + failure.body());
        }

        List<ILoggingEvent> events = new ArrayList<>(journal.list);
        assertFalse(events.isEmpty(), "контроль: перехват журнала работает, события есть");
        assertTrue(events.stream().anyMatch(e -> e.getFormattedMessage().contains(issued)), "контроль: журнал знает заказ, но не адрес");
        for (ILoggingEvent event : events) {
            String text = render(event);
            assertFalse(text.contains("canary-"), "в журнале нашёлся адрес: " + text);
        }

        String metrics = monitoring.get("/actuator/prometheus").body();
        assertTrue(metrics.contains("orders_address_updated_total"), "контроль: метрика обновления есть");
        assertFalse(metrics.contains("canary-"), "в метриках нашёлся адрес");
        assertNotEquals(0, addressEvents(issued).size());
    }

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

    // ------------------------------------------------------------------------------------------------ TC-529

    @Tag("TC-529")
    @Test
    void onlyPlatformServiceMayCallAndTheKeyAndTheBodyAreChecked() throws IOException {
        String orderId = placeIssued();
        String original = address(orderId);
        int version = version(orderId);
        String path = INTERNAL + orderId + OPERATION;
        String valid = body(ticket(), canary());

        Response gatewayCall = gateway.post(path, valid, "Idempotency-Key", key());
        assertEquals(403, gatewayCall.status(), gatewayCall.body());
        assertEquals("forbidden", code(gatewayCall));
        assertThrows(IOException.class, () -> StandHttp.withoutCertificate(PORT).post(path, valid, "Idempotency-Key", key()));
        StandHttp impostor = StandHttp.withForeignCertificate("platform-service", PORT);
        assertThrows(IOException.class, () -> impostor.post(path, valid, "Idempotency-Key", key()));

        Response noKey = platform.post(path, valid);
        assertEquals(400, noKey.status(), noKey.body());
        assertEquals("idempotency-key-required", code(noKey));
        Response badKey = platform.post(path, valid, "Idempotency-Key", "not-a-uuid");
        assertEquals(400, badKey.status(), badKey.body());
        assertEquals("bad-request", code(badKey));

        Response invalid = platform.post(path, "{\"ticketId\":\"x\",\"email\":\"y\",\"extra\":1}", "Idempotency-Key", key());
        assertEquals(422, invalid.status(), invalid.body());
        assertEquals("validation-failed", code(invalid));
        List<?> errors = (List<?>) invalid.json().get("errors");
        assertEquals(List.of("/extra", "/ticketId", "/email"), errors.stream().map(e -> ((Map<?, ?>) e).get("pointer")).toList());
        Response missing = platform.post(path, "{}", "Idempotency-Key", key());
        assertEquals(422, missing.status(), missing.body());
        Response text = platform.request("POST", path, "text/plain", valid.getBytes(StandardCharsets.UTF_8), "Idempotency-Key", key());
        assertEquals(415, text.status(), text.body());
        Response broken = platform.post(path, "not json", "Idempotency-Key", key());
        assertEquals(400, broken.status(), broken.body());
        Response badId = platform.post(INTERNAL + orderId.toUpperCase() + OPERATION, valid, "Idempotency-Key", key());
        assertEquals(400, badId.status(), badId.body());

        assertEquals(original, address(orderId), "ни один отказ заказ не изменил");
        assertEquals(version, version(orderId));
        assertEquals(0, addressEvents(orderId).size());
    }
}
