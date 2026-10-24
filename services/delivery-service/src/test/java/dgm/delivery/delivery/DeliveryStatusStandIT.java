package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretFiles;
import dgm.kit.time.Timestamps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;

/**
 * Общая основа тестов статусов писем и контроля 30 минут на стенде: вызывающий вебхук это {@code api-gateway} (подпись настоящим секретом
 * {@code delivery_webhook_secret}), письмо принимает почтовая заглушка, которая вебхуков сама не шлёт (режим {@code status = none}): уведомление
 * присылает тест, с любыми дефектами подписи, времени и тела. Опрос статусов и контроль вызывает тест ({@link #poller}, {@link #watch}).
 */
abstract class DeliveryStatusStandIT extends DeliveryStandIT {

    static final String PATH = "/api/v1/webhooks/email-provider-keys";

    static StandHttp gatewayCaller;
    static byte[] webhookSecret;

    @BeforeAll
    static void connectWebhook() {
        gatewayCaller = StandHttp.asService("api-gateway", PORT);
        webhookSecret = SecretFiles.read("delivery_webhook_secret").revealBytes();
    }

    // ------------------------------------------------------------------------------------------------ вебхук

    /** Тело уведомления по схеме {@code EmailStatusNotification}; причина нужна только статусу {@code failed}. */
    static Map<String, Object> notice(Object messageId, String status, Instant occurredAt, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", String.valueOf(messageId));
        body.put("status", status);
        body.put("occurredAt", Timestamps.format(occurredAt));
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    static Map<String, Object> notice(Object messageId, String status) {
        return notice(messageId, status, Instant.now().truncatedTo(ChronoUnit.MILLIS), null);
    }

    static String sign(String timestamp, String body) {
        return WebhookSignature.sign(webhookSecret, timestamp, body.getBytes(StandardCharsets.UTF_8));
    }

    /** Подписанный запрос шлюза. */
    static Response send(Map<String, Object> body) throws IOException {
        return send(Json.write(body));
    }

    static Response send(String body) throws IOException {
        String timestamp = Timestamps.format(Instant.now());
        return gatewayCaller.post(PATH, body, WebhookSignature.TIMESTAMP_HEADER, timestamp, WebhookSignature.SIGNATURE_HEADER, sign(timestamp, body));
    }

    static String code(Response response) {
        return (String) response.json().get("code");
    }

    /** Провайдер у себя считает письмо доставленным или отказанным, а вебхука не шлёт: платформа узнает об этом только опросом. */
    static void providerSays(UUID messageId, String status, String reason) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("send", false);
        if (reason != null) {
            fields.put("reason", reason);
        }
        stubs.emailStatus(messageId.toString(), status, fields);
    }

    // ------------------------------------------------------------------------------------------------ выдачи

    /** Ключи выданы, письмо принято провайдером: выдача заказа «отправлено». Возвращает идентификатор выдачи, он же идентификатор письма. */
    UUID sent(Placed p) {
        INVENTORY.issue(p.orderId(), p.quantity());
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))), "выдача не стала «отправлено», сейчас " + status(p));
        return idOf(p);
    }

    /** Оплаченный в {@code paidAt} заказ приходит событием; метод ждёт первичную выдачу. */
    Placed placeAt(Instant paidAt, int quantity) throws Exception {
        Placed p = newOrder(quantity);
        publishPaid(p, paidAt.truncatedTo(ChronoUnit.MILLIS), UUID.randomUUID());
        assertTrue(await(LIMIT, () -> deliveryOf(p) != null), "выдача заказа " + p.orderId() + " не создана по order.paid");
        return p;
    }

    /**
     * Выдача «отправлено», созданная без Kafka и почты: письмо принято {@code sentAgo} назад, контроль открыт со сроком {@code deadline}. Для гонок и опроса:
     * быстро и без отставания потребителя.
     */
    Placed seedSent(Duration sentAgo, Instant deadline) {
        Placed p = newOrder(1);
        UUID id = UUID.randomUUID();
        db.update("insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, next_attempt_at) "
                + "values (?, ?, ?, 'primary', ?, ?, ?, now() + interval '1 day')", id, p.orderId(), p.buyerId(), p.email(), p.title(), p.quantity());
        db.update("update delivery.delivery set status = 'sent', attempt_no = 1, next_attempt_at = null, sent_at = now() - make_interval(secs => ?) where id = ?",
                (double) sentAgo.toSeconds(), id);
        db.update("insert into delivery.delivery_watch (order_id, deadline_at) values (?, ?)", p.orderId(), Timestamp.from(deadline));
        return p;
    }

    static long count(String sql) {
        Long n = db.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /** Состояние контроля заказа или {@code null}. */
    static String watchStatus(Placed p) {
        List<String> rows = db.queryForList("select status from delivery.delivery_watch where order_id = ?", String.class, p.orderId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Время доставки выдачи или {@code null}. */
    static Instant deliveredAt(Placed p) {
        Timestamp at = db.queryForObject("select delivered_at from delivery.delivery where id = ?", Timestamp.class, idOf(p));
        return at == null ? null : at.toInstant();
    }

    /** Принятые статусы письма провайдера, по одному на пару «выдача, статус», в алфавитном порядке. */
    static List<String> providerStatuses(UUID deliveryId) {
        return db.queryForList("select provider_status from delivery.provider_status_event where delivery_id = ? order by provider_status", String.class,
                deliveryId);
    }

    /** Сколько событий {@code delivery.delivered}, {@code delivery.failed} и {@code delivery.overdue} у заказа, всего. */
    static int outcomeEvents(Placed p) {
        return events(p, "delivery.delivered").size() + events(p, "delivery.failed").size() + events(p, "delivery.overdue").size();
    }

    /** Проходы опроса, пока условие не выполнится: опрос идёт по кругу порциями, а выдач с прошлых прогонов на стенде может быть много. */
    boolean poll(Duration limit, BooleanSupplier done) {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (done.getAsBoolean()) {
                return true;
            }
            poller.runOnce();
            pause(50);
        }
        return done.getAsBoolean();
    }

    /** Проходы контроля, пока условие не выполнится: порция ограничена, а просроченных контролей с прошлых прогонов на стенде может быть много. */
    boolean keepWatch(Duration limit, BooleanSupplier done) {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            if (done.getAsBoolean()) {
                return true;
            }
            watch.runOnce();
            pause(50);
        }
        return done.getAsBoolean();
    }

    static void assertOk(Response response) {
        assertEquals(200, response.status(), response.body());
        assertTrue(response.body() == null || response.body().isEmpty(), "тело ответа вебхука пустое: " + response.body());
    }
}
