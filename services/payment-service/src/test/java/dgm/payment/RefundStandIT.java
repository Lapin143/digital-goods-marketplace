package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Общая основа тестов возвратов на стенде: команда {@code order.refund-requested} от имени {@code order-service} (права Kafka выданы по CN
 * сертификата), платёж с подтверждённой оплатой, режимы возврата заглушки шлюза, запросы к попыткам возврата и Outbox, токен администратора.
 * Автовозврат идёт фоновым исполнителем сервиса с паузами 0 и четыре по секунде (свойства в {@link PaymentStandIT}); ждать его итог нужно
 * опросом базы.
 */
abstract class RefundStandIT extends PaymentWebhookStandIT {

    static final String MANUAL = "/api/v1/staff/manual-refunds";
    static final UUID ADMIN = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    static KafkaProducer<String, String> orderEvents;

    @BeforeAll
    static void connectKafka() {
        orderEvents = KafkaClients.producer(Stand.kafkaBootstrap(), Stand.kafkaSecurity("order-service"), "order-service-refund-it");
    }

    @AfterAll
    static void closeKafka() {
        orderEvents.close(Duration.ofSeconds(5));
    }

    /** Режим возврата заглушки шлюза: {@code instant}, {@code pending} или {@code reject}, первые {@code failFirst} запросов получают 503. */
    static void refundMode(String mode, int failFirst) {
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("mode", mode);
        refund.put("failFirst", failFirst);
        stubs.setMode("payment", Map.of("scenario", "hold", "refund", refund));
    }

    /** Платёж, оплаченный и подтверждённый уведомлением шлюза. */
    static Opened confirmed() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        Response response = send(paid(p));
        assertEquals(200, response.status(), response.body());
        assertEquals("confirmed", status(p.orderId()));
        return p;
    }

    static UUID paymentId(UUID orderId) {
        return (UUID) payment(orderId).get("id");
    }

    /** Публикует {@code order.refund-requested}; возвращает идентификатор события. */
    static UUID requestRefund(Opened p, String reason) throws Exception {
        return requestRefund(p.orderId(), AMOUNT, reason);
    }

    static UUID requestRefund(UUID orderId, long amount, String reason) throws Exception {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", "RUB");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("amount", money);
        data.put("refundReason", reason);
        UUID eventId = UUID.randomUUID();
        String headers = "{\"schemaversion\": 1, \"traceparent\": \"" + TraceContext.fresh().traceparent() + "\", \"correlationid\": \"" + UUID.randomUUID() + "\"}";
        OutgoingEvent event = new EnvelopeBuilder("order-service").build(new OutboxRow(1, eventId, "Order", orderId.toString(), "order.refund-requested",
                "order.events", Json.write(data), headers, Instant.now(), 0));
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        orderEvents.send(record).get(20, TimeUnit.SECONDS);
        return eventId;
    }

    /** Потребитель {@code refund-service} обработал событие (запись в {@code processed_event}). */
    static boolean processed(UUID eventId) {
        Integer n = db.queryForObject("select count(*) from public.processed_event where consumer = 'refund-service' and event_id = ?", Integer.class, eventId);
        return n != null && n > 0;
    }

    /** Попытки возврата платежа по номерам. */
    static List<Map<String, Object>> attempts(UUID paymentId) {
        return db.queryForList("select attempt_no, status, error_code, next_attempt_at, finished_at from payments.refund_attempt where payment_id = ? "
                + "order by attempt_no", paymentId);
    }

    /** Поля возврата платежа. */
    static Map<String, Object> refundRow(UUID orderId) {
        return db.queryForList("select status, refund_reason, gateway_refund_id, in_admin_queue, refund_escalated_at, refunded_at from payments.payment "
                + "where order_id = ?", orderId).get(0);
    }

    /** События Outbox по агрегату: тип и тело. */
    static List<Map<String, Object>> published(String aggregateId, String eventType) {
        return db.queryForList("select event_type, topic, payload::text as payload from public.outbox where aggregate_id = ? and event_type = ? order by id",
                aggregateId, eventType);
    }

    static Map<String, Object> payloadOf(Map<String, Object> outboxRow) {
        return Json.parseObject((String) outboxRow.get("payload"));
    }

    /** Заглушка не отдавала деньги: у платежа шлюза возврата нет. */
    static Map<String, Object> gatewayPayment(Opened p) {
        return stubs.payment(p.gatewayId());
    }

    /** Платёж, по которому автовозврат исчерпал пять попыток и ждёт администратора (шлюз отказывает в возврате всегда). */
    static Opened escalated() throws Exception {
        refundMode("reject", 0);
        Opened p = confirmed();
        requestRefund(p, "late_payment");
        assertTrue(await(LIMIT, () -> Boolean.TRUE.equals(refundRow(p.orderId()).get("in_admin_queue"))), "возврат не попал в очередь администратора");
        return p;
    }

    static String[] adminAuth() {
        return StandHttp.bearer(TOKENS.token().subject(ADMIN.toString()).scopes("staff.admin").roles("admin").amr("pwd", "otp").build());
    }

    static String[] with(String[] auth, String... extra) {
        String[] all = new String[auth.length + extra.length];
        System.arraycopy(auth, 0, all, 0, auth.length);
        System.arraycopy(extra, 0, all, auth.length, extra.length);
        return all;
    }

    static Response complete(UUID paymentId, String body, String idempotencyKey) throws java.io.IOException {
        return gatewayCaller.post(MANUAL + "/" + paymentId + "/complete", body, with(adminAuth(), "Idempotency-Key", idempotencyKey));
    }
}
