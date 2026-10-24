package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.time.Timestamps;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.reconcile.PaymentReconciliation;
import dgm.payment.payments.reconcile.PaymentReconciliation.Result;
import dgm.payment.payments.reconcile.UnmatchedNotificationJob;
import dgm.payment.payments.repository.PaymentRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Вебхук платёжного шлюза на стенде (ADR-015, FT-6.2, INV-14, ST-04): настоящая база, настоящая заглушка шлюза, подпись настоящим секретом
 * {@code payment_webhook_secret}. Сверка и сопоставление несопоставленных уведомлений вызываются явно: фоновые проходы в тестах отложены,
 * иначе они подтвердили бы платёж, который тест держит в статусе «создан». Гонки одного уведомления в {@link PaymentWebhookConcurrencyIT}.
 */
class PaymentWebhookIT extends PaymentWebhookStandIT {

    @Autowired
    PaymentRepository repository;
    @Autowired
    PaymentReconciliation reconciliation;
    @Autowired
    UnmatchedNotificationJob unmatchedJob;

    // ------------------------------------------------------------------------------------------------ TC-502, TC-503

    @Tag("TC-502")
    @Test
    void aPaidNotificationConfirmsThePaymentAndWritesOneEventToTheOutbox() throws Exception {
        Opened p = opened();
        gatewayPays(p);

        Response response = send(paid(p));

        assertEquals(200, response.status(), response.body());
        assertEquals("confirmed", status(p.orderId()));
        assertNotNull(db.queryForObject("select confirmed_at from payments.payment where order_id = ?", java.sql.Timestamp.class, p.orderId()));
        List<Map<String, Object>> events = eventsOf(p.gatewayId());
        assertEquals(1, events.size());
        assertEquals("payment.paid", events.get(0).get("event_type"));
        assertEquals("applied", events.get(0).get("outcome"));
        List<Map<String, Object>> published = outbox(p.orderId(), "payment.confirmed");
        assertEquals(1, published.size());
        assertEquals("payment.events", published.get(0).get("topic"));
        assertEquals("Payment", published.get(0).get("aggregate_type"));
        Map<String, Object> data = Json.parseObject((String) published.get(0).get("payload"));
        assertEquals(payment(p.orderId()).get("id").toString(), data.get("paymentId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals(Map.of("amount", (int) AMOUNT, "currency", "RUB"), data.get("amount"));
        assertNotNull(data.get("confirmedAt"));
        assertEquals(0, outbox(p.orderId(), "payment.rejected").size());
    }

    @Tag("TC-503")
    @Test
    void aDeclinedNotificationRejectsThePaymentWithTheReason() throws Exception {
        Opened p = opened();
        stubs.declinePayment(p.gatewayId(), Map.of("send", false));
        Map<String, Object> body = notification("payment.declined", "evt_" + UUID.randomUUID(), p, AMOUNT, "RUB");
        dataOf(body).put("failureReason", "insufficient_funds");

        Response response = send(body);

        assertEquals(200, response.status(), response.body());
        assertEquals("rejected", status(p.orderId()));
        assertNotNull(db.queryForObject("select rejected_at from payments.payment where order_id = ?", java.sql.Timestamp.class, p.orderId()));
        List<Map<String, Object>> published = outbox(p.orderId(), "payment.rejected");
        assertEquals(1, published.size());
        Map<String, Object> data = Json.parseObject((String) published.get(0).get("payload"));
        assertEquals("insufficient_funds", data.get("reason"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals(0, outbox(p.orderId(), "payment.confirmed").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-504

    @Tag("TC-504")
    @Test
    void aRepeatedNotificationChangesNothingAndANoticeAgainstTheOutcomeIsAnAnomaly() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        Map<String, Object> body = paid(p);
        assertEquals(200, send(body).status());
        assertEquals(200, send(body).status());
        assertEquals(200, send(paid(p)).status(), "то же событие под другим идентификатором шлюза это тот же тип: повтор");

        assertEquals("confirmed", status(p.orderId()));
        assertEquals(1, eventsOf(p.gatewayId()).size());
        assertEquals(1, outbox(p.orderId(), "payment.confirmed").size());

        Map<String, Object> declined = notification("payment.declined", "evt_" + UUID.randomUUID(), p, AMOUNT, "RUB");
        assertEquals(200, send(declined).status(), "отказ после подтверждения не повод повторять уведомление");

        assertEquals("confirmed", status(p.orderId()), "SM-02: из подтверждённого в отклонённый перехода нет");
        List<Map<String, Object>> events = eventsOf(p.gatewayId());
        assertEquals(2, events.size());
        assertEquals("anomaly", events.get(0).get("outcome"));
        assertEquals("payment.declined", events.get(0).get("event_type"));
        assertEquals("applied", events.get(1).get("outcome"));
        assertEquals(1, outboxCount(p.orderId()), "второго события Outbox нет");
    }

    @Tag("TC-504")
    @Test
    void sessionExpiryAndUnknownTypesAreAcceptedAndChangeNothing() throws Exception {
        Opened p = opened();
        for (String type : new String[] {"payment.session-expired", "payment.something-new"}) {
            Response response = send(notification(type, "evt_" + UUID.randomUUID(), p, AMOUNT, "RUB"));
            assertEquals(200, response.status(), type + ": " + response.body());
        }
        assertEquals("created", status(p.orderId()), "истечение сессии статус не меняет: на случай поздней оплаты (SM-01/T6)");
        assertEquals(0, eventsOf(p.gatewayId()).size());
        assertEquals(0, outboxCount(p.orderId()));
    }

    @Tag("TC-504")
    @Test
    void aRefundNoticeForAPaymentThatWasNeverRefundedIsAnAnomalyAndChangesNothing() throws Exception {
        Opened p = opened();
        Response response = send(notification("refund.completed", "evt_" + UUID.randomUUID(), p, AMOUNT, "RUB"));

        assertEquals(200, response.status(), response.body());
        assertEquals("created", status(p.orderId()));
        List<Map<String, Object>> events = eventsOf(p.gatewayId());
        assertEquals(1, events.size());
        assertEquals("anomaly", events.get(0).get("outcome"));
        assertEquals(0, outboxCount(p.orderId()));
    }

    // ------------------------------------------------------------------------------------------------ TC-505

    @Tag("TC-505")
    @Test
    void anotherAmountOrCurrencyLeavesTheStatusAndIsRecorded() throws Exception {
        Opened p = opened();
        gatewayPays(p);

        assertEquals(200, send(notification("payment.paid", "evt_a", p, AMOUNT - 1, "RUB")).status());
        assertEquals("created", status(p.orderId()));
        assertEquals("amount_mismatch", eventsOf(p.gatewayId()).get(0).get("outcome"));

        Opened q = opened();
        gatewayPays(q);
        assertEquals(200, send(notification("payment.paid", "evt_b", q, AMOUNT, "USD")).status());
        assertEquals("created", status(q.orderId()));
        assertEquals("amount_mismatch", eventsOf(q.gatewayId()).get(0).get("outcome"));
        assertEquals(0, outboxCount(p.orderId()) + outboxCount(q.orderId()));
    }

    @Tag("TC-505")
    @Test
    void aPaidNoticeThatTheGatewayDoesNotConfirmLeavesThePaymentCreated() throws Exception {
        Opened p = opened();
        // у шлюза платёж ещё ждёт оплаты: подписанное уведомление «оплачен» ему противоречит
        Response response = send(paid(p));

        assertEquals(200, response.status(), response.body());
        assertEquals("created", status(p.orderId()));
        List<Map<String, Object>> events = eventsOf(p.gatewayId());
        assertEquals(1, events.size());
        assertEquals("anomaly", events.get(0).get("outcome"));
        assertEquals(0, outboxCount(p.orderId()));
    }

    @Tag("TC-505")
    @Test
    void aNoticeNamingAnotherOrderIsAnAnomalyAndTheRealOrderIsNotTouched() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        Map<String, Object> body = paid(p);
        dataOf(body).put("orderId", UUID.randomUUID().toString());

        assertEquals(200, send(body).status());

        assertEquals("created", status(p.orderId()));
        assertEquals("anomaly", eventsOf(p.gatewayId()).get(0).get("outcome"));
    }

    // ------------------------------------------------------------------------------------------------ TC-506

    @Tag("TC-506")
    @Test
    void aForgedOrStaleNotificationIsRefusedAndNothingIsWritten() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        String body = Json.write(paid(p));
        String now = Timestamps.format(Instant.now());

        Response wrongSecret = gatewayCaller.post(PATH, body, WebhookSignature.TIMESTAMP_HEADER, now, WebhookSignature.SIGNATURE_HEADER, "0".repeat(64));
        assertEquals(401, wrongSecret.status(), wrongSecret.body());
        assertEquals("signature-invalid", code(wrongSecret));

        Response tampered = gatewayCaller.post(PATH, body.replace("payment.paid", "payment.paiD"), WebhookSignature.TIMESTAMP_HEADER, now,
                WebhookSignature.SIGNATURE_HEADER, sign(now, body));
        assertEquals(401, tampered.status());

        Response noHeaders = gatewayCaller.post(PATH, body);
        assertEquals(401, noHeaders.status());
        assertEquals("signature-invalid", code(noHeaders));

        String old = Timestamps.format(Instant.now().minus(Duration.ofMinutes(30)));
        Response stale = gatewayCaller.post(PATH, body, WebhookSignature.TIMESTAMP_HEADER, old, WebhookSignature.SIGNATURE_HEADER, sign(old, body));
        assertEquals(401, stale.status());
        assertEquals("webhook-expired", code(stale), "подпись верна, метка старше пяти минут: повторная отправка перехваченного тела");

        assertEquals("created", status(p.orderId()));
        assertEquals(0, eventsOf(p.gatewayId()).size());
        assertEquals(0, outboxCount(p.orderId()));
    }

    /** ST-08 и ST-04: верно подписанное уведомление от «шлюза платформы» с сертификатом чужого центра не доходит до обработчика. */
    @Test
    void aSignedNotificationFromAClientWithAForeignCertificateNeverReachesTheHandler() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        String body = Json.write(paid(p));
        String now = Timestamps.format(Instant.now());
        StandHttp impostor = StandHttp.withForeignCertificate("api-gateway", PORT);

        assertThrows(IOException.class, () -> impostor.post(PATH, body, WebhookSignature.TIMESTAMP_HEADER, now, WebhookSignature.SIGNATURE_HEADER,
                sign(now, body)));

        assertEquals("created", status(p.orderId()));
        assertEquals(0, eventsOf(p.gatewayId()).size());
        assertEquals(0, outboxCount(p.orderId()));
    }

    @Tag("TC-506")
    @Test
    void aSignedBodyThatBreaksTheSchemaIs400AndAWrongContentTypeIs415() throws Exception {
        Opened p = opened();
        gatewayPays(p);

        Map<String, Object> extra = paid(p);
        extra.put("surprise", 1);
        Response extraField = send(extra);
        assertEquals(400, extraField.status(), extraField.body());
        assertEquals("bad-request", code(extraField));

        Map<String, Object> noOrder = paid(p);
        dataOf(noOrder).remove("orderId");
        assertEquals(400, send(noOrder).status());

        Response notJson = send("{не JSON");
        assertEquals(400, notJson.status());

        String body = Json.write(paid(p));
        String now = Timestamps.format(Instant.now());
        Response plain = gatewayCaller.request("POST", PATH, "text/plain", body.getBytes(StandardCharsets.UTF_8), WebhookSignature.TIMESTAMP_HEADER, now,
                WebhookSignature.SIGNATURE_HEADER, sign(now, body));
        assertEquals(415, plain.status(), plain.body());
        assertEquals("unsupported-media-type", code(plain));

        assertEquals("created", status(p.orderId()));
        assertEquals(0, eventsOf(p.gatewayId()).size());
    }

    @Tag("TC-506")
    @Test
    void aBodyLargerThanSixtyFourKilobytesIs413() throws Exception {
        String body = "{\"eventId\":\"" + "x".repeat(70_000) + "\"}";
        Response response = send(body);
        assertEquals(413, response.status(), response.body());
    }

    // ------------------------------------------------------------------------------------------------ TC-507

    @Tag("TC-507")
    @Test
    void aNoticeForAnUnknownPaymentIsAcceptedStoredOnceAndAlertedAfterAnHour() throws Exception {
        UUID orderId = UUID.randomUUID();
        Opened unknown = new Opened(orderId, "pay_unknown_" + UUID.randomUUID());
        Map<String, Object> body = notification("payment.paid", "evt_" + UUID.randomUUID(), unknown, AMOUNT, "RUB");

        assertEquals(200, send(body).status());
        assertEquals(200, send(body).status());

        List<Map<String, Object>> rows = db.queryForList("select id, matched_at, alert_sent_at, order_id from payments.unmatched_notification where gateway_payment_id = ?",
                unknown.gatewayId());
        assertEquals(1, rows.size(), "повтор того же уведомления не создаёт второй записи");
        assertNull(rows.get(0).get("matched_at"));
        assertEquals(0, paymentsOf(orderId), "платёж по неизвестному уведомлению не создаётся");

        // уведомление ждёт дольше часа: планировщик поднимает оповещение один раз
        db.update("update payments.unmatched_notification set received_at = now() - interval '2 hours' where gateway_payment_id = ?", unknown.gatewayId());
        unmatchedJob.runPass();
        unmatchedJob.runPass();
        Map<String, Object> alerted = db.queryForMap("select matched_at, alert_sent_at from payments.unmatched_notification where gateway_payment_id = ?", unknown.gatewayId());
        assertNull(alerted.get("matched_at"));
        assertNotNull(alerted.get("alert_sent_at"));
        // платёж по этому уведомлению никогда не появится: запись убирается, иначе она вечно занимает место в проходе планировщика
        db.update("delete from payments.unmatched_notification where gateway_payment_id = ?", unknown.gatewayId());
    }

    @Tag("TC-507")
    @Test
    void anUnmatchedNoticeIsAppliedThroughTheGatewayStatusWhenThePaymentAppears() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        UUID noticeId = UUID.randomUUID();
        db.update("insert into payments.unmatched_notification (id, gateway_payment_id, event_type, gateway_event_id, order_id, occurred_at, received_at) "
                + "values (?, ?, 'payment.paid', 'evt_early', ?, now(), now() - interval '2 hours')", noticeId, p.gatewayId(), p.orderId());
        db.update("update payments.unmatched_notification set alert_sent_at = now() where id = ?", noticeId);

        unmatchedJob.runPass();

        assertEquals("confirmed", status(p.orderId()));
        Map<String, Object> row = db.queryForMap("select matched_at, alert_sent_at from payments.unmatched_notification where id = ?", noticeId);
        assertNotNull(row.get("matched_at"));
        assertNull(row.get("alert_sent_at"), "сопоставленное уведомление оповещения не несёт (ограничение базы)");
        assertEquals(1, outbox(p.orderId(), "payment.confirmed").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-508

    @Tag("TC-508")
    @Test
    void whenTheGatewayDoesNotAnswerTheStatusRequestTheAnswerIs503AndTheRepeatIsApplied() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        Map<String, Object> body = paid(p);

        Response refused = stubs.supplyInMode("payment", Map.of("scenario", "hold", "status", Map.of("failFirst", -1, "failure", "http_503")), () -> {
            try {
                return send(body);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(503, refused.status(), refused.body());
        assertEquals("dependency-unavailable", code(refused));
        assertEquals("5", refused.header("Retry-After"));
        assertEquals("created", status(p.orderId()));
        assertEquals(0, eventsOf(p.gatewayId()).size(), "ничего не записано: шлюз повторит это же уведомление");

        Response repeat = send(body);
        assertEquals(200, repeat.status(), repeat.body());
        assertEquals("confirmed", status(p.orderId()));
        assertEquals(1, outbox(p.orderId(), "payment.confirmed").size());
    }

    @Tag("TC-508")
    @Test
    void aLostNotificationIsHealedByTheReconciliationWithTheSameEffectAsTheWebhook() throws Exception {
        Opened p = opened();
        gatewayPays(p);

        Payment open = repository.findByOrder(p.orderId()).orElseThrow();
        assertTrue(repository.openBetween(Instant.now().minus(Duration.ofHours(1)), Instant.now().plusSeconds(60), 10_000)
                .stream().anyMatch(x -> x.orderId().equals(p.orderId())), "платёж без вебхука попадает в выборку сверки");
        assertEquals(Result.CONFIRMED, reconciliation.reconcile(open));

        assertEquals("confirmed", status(p.orderId()));
        List<Map<String, Object>> events = eventsOf(p.gatewayId());
        assertEquals(1, events.size());
        assertEquals("reconcile.paid", events.get(0).get("event_type"));
        assertEquals(1, outbox(p.orderId(), "payment.confirmed").size());

        // запоздавший вебхук после сверки: повтор исхода, не аномалия, второго события нет
        assertEquals(200, send(paid(p)).status());
        assertEquals("confirmed", status(p.orderId()));
        assertEquals(1, outbox(p.orderId(), "payment.confirmed").size());
        assertFalse(eventsOf(p.gatewayId()).stream().anyMatch(e -> "anomaly".equals(e.get("outcome"))));
        assertEquals(Result.UNCHANGED, reconciliation.reconcile(repository.findByOrder(p.orderId()).orElseThrow()));
    }

    @Tag("TC-508")
    @Test
    void reconciliationRejectsWhatTheGatewayDeclinedAndLeavesWaitingPaymentsAlone() throws Exception {
        Opened declined = opened();
        stubs.declinePayment(declined.gatewayId(), Map.of("send", false));
        Opened waiting = opened();

        assertEquals(Result.REJECTED, reconciliation.reconcile(repository.findByOrder(declined.orderId()).orElseThrow()));
        assertEquals(Result.UNCHANGED, reconciliation.reconcile(repository.findByOrder(waiting.orderId()).orElseThrow()));

        assertEquals("rejected", status(declined.orderId()));
        assertEquals("created", status(waiting.orderId()));
        assertEquals(1, outbox(declined.orderId(), "payment.rejected").size());
    }
}
