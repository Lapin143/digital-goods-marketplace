package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretFiles;
import dgm.kit.time.Timestamps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;

/**
 * Общая основа тестов вебхука шлюза на стенде: вызывающий это {@code api-gateway}, подпись настоящим секретом
 * {@code payment_webhook_secret}, готовый платёж с открытой сессией, запросы к базе платежей и Outbox. Заглушка сама вебхуков не шлёт
 * (режим {@code hold}, адреса получателя нет): тест меняет статус платежа у шлюза командой {@code send=false} и присылает уведомление
 * сам, с любыми дефектами подписи, времени, суммы и порядка.
 */
abstract class PaymentWebhookStandIT extends PaymentStandIT {

    static final String PATH = "/api/v1/webhooks/payment-gateway";
    static final long AMOUNT = 299800;

    static StandHttp gatewayCaller;
    static byte[] secret;

    @BeforeAll
    static void connectWebhook() {
        gatewayCaller = StandHttp.asService("api-gateway", PORT);
        secret = SecretFiles.read("payment_webhook_secret").revealBytes();
    }

    /** Платёж с открытой сессией: заказ и внешний идентификатор у шлюза. */
    record Opened(UUID orderId, String gatewayId) {
    }

    static Opened opened() throws IOException {
        UUID orderId = UUID.randomUUID();
        Response response = open(orderId, AMOUNT);
        assertEquals(201, response.status(), response.body());
        Map<String, Object> row = payment(orderId);
        return new Opened(orderId, (String) row.get("gateway_payment_id"));
    }

    /** Тело уведомления шлюза по схеме {@code PaymentGatewayNotification}. */
    static Map<String, Object> notification(String type, String eventId, Opened p, long amount, String currency) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", p.gatewayId());
        data.put("orderId", p.orderId().toString());
        data.put("amount", money);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("type", type);
        body.put("occurredAt", Timestamps.format(Instant.now()));
        body.put("data", data);
        return body;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> dataOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    static Map<String, Object> paid(Opened p) {
        return notification("payment.paid", "evt_" + UUID.randomUUID(), p, AMOUNT, "RUB");
    }

    static String sign(String timestamp, String body) {
        return WebhookSignature.sign(secret, timestamp, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Подписанный запрос шлюза. */
    static Response send(Map<String, Object> body) throws IOException {
        return send(Json.write(body));
    }

    static Response send(String body) throws IOException {
        String timestamp = Timestamps.format(Instant.now());
        return gatewayCaller.post(PATH, body, WebhookSignature.TIMESTAMP_HEADER, timestamp, WebhookSignature.SIGNATURE_HEADER, sign(timestamp, body));
    }

    /** Платёж у шлюза становится оплаченным, вебхук не уходит: уведомление присылает тест. */
    static void gatewayPays(Opened p) {
        stubs.confirmPayment(p.gatewayId(), Map.of("send", false));
    }

    static String status(UUID orderId) {
        return (String) payment(orderId).get("status");
    }

    /** Строки {@code payment_event} платежа: тип события и исход. */
    static List<Map<String, Object>> eventsOf(String gatewayId) {
        return db.queryForList("select event_type, outcome, gateway_event_id from payments.payment_event where gateway_payment_id = ? order by event_type", gatewayId);
    }

    /** События Outbox по заказу: тип и тело. */
    static List<Map<String, Object>> outbox(UUID orderId, String eventType) {
        return db.queryForList("select event_type, topic, aggregate_type, payload::text as payload from public.outbox where aggregate_id = ? and event_type = ? order by id",
                orderId.toString(), eventType);
    }

    static long outboxCount(UUID orderId) {
        Long n = db.queryForObject("select count(*) from public.outbox where aggregate_id = ? and event_type in ('payment.confirmed', 'payment.rejected')", Long.class,
                orderId.toString());
        return n == null ? 0 : n;
    }
}
