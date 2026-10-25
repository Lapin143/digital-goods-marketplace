package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.payment.payments.refund.RefundReconciler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Возврат денег на стенде (ADR-015, ADR-013, FT-6.3, FT-6.4, INV-15): команда {@code order.refund-requested} из Kafka, фоновый исполнитель
 * с паузами 0 и четыре по секунде, настоящая заглушка шлюза в режимах {@code instant}, {@code pending} и {@code reject}, подтверждение
 * подписанным вебхуком и сверкой, передача администратору после пятого отказа, ручная отметка с записью аудита. Гонки в
 * {@link RefundConcurrencyIT}.
 */
class RefundIT extends RefundStandIT {

    @Autowired
    RefundReconciler reconciler;

    // ------------------------------------------------------------------------------------------------ TC-521, TC-522

    @Tag("TC-521")
    @Tag("TC-522")
    @Test
    void anInstantRefundMakesThePaymentRefundedAndWritesOneEvent() throws Exception {
        refundMode("instant", 0);
        Opened p = confirmed();
        UUID paymentId = paymentId(p.orderId());

        UUID command = requestRefund(p, "late_payment");

        assertTrue(await(LIMIT, () -> "refunded".equals(status(p.orderId()))), "платёж не стал возвращённым");
        assertTrue(processed(command));
        Map<String, Object> row = refundRow(p.orderId());
        assertEquals("late_payment", row.get("refund_reason"));
        assertNotNull(row.get("gateway_refund_id"));
        assertNotNull(row.get("refunded_at"));
        assertFalse((Boolean) row.get("in_admin_queue"));
        List<Map<String, Object>> attempts = attempts(paymentId);
        assertEquals(1, attempts.size());
        assertEquals("succeeded", attempts.get(0).get("status"));
        assertNull(attempts.get(0).get("error_code"));
        assertEquals("refunded", gatewayPayment(p).get("status"), "у шлюза деньги возвращены");

        List<Map<String, Object>> events = published(p.orderId().toString(), "payment.refunded");
        assertEquals(1, events.size());
        assertEquals("payment.events", events.get(0).get("topic"));
        Map<String, Object> data = payloadOf(events.get(0));
        assertEquals(paymentId.toString(), data.get("paymentId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals(Map.of("amount", (int) AMOUNT, "currency", "RUB"), data.get("amount"));
        assertEquals("late_payment", data.get("refundReason"));
        assertEquals(false, data.get("manual"));
        assertNotNull(data.get("refundedAt"));
    }

    @Tag("TC-521")
    @Test
    void aRepeatedCommandAfterTheRefundIsIgnoredAndCreatesNoSecondRefund() throws Exception {
        refundMode("instant", 0);
        Opened p = confirmed();
        requestRefund(p, "late_payment");
        assertTrue(await(LIMIT, () -> "refunded".equals(status(p.orderId()))));
        Object refundId = refundRow(p.orderId()).get("gateway_refund_id");

        UUID second = requestRefund(p, "late_payment");
        UUID third = requestRefund(p, "dispute_decision");

        assertTrue(await(LIMIT, () -> processed(second) && processed(third)), "повторные команды не обработаны");
        assertEquals(1, attempts(paymentId(p.orderId())).size());
        assertEquals(1, published(p.orderId().toString(), "payment.refunded").size());
        assertEquals(refundId, refundRow(p.orderId()).get("gateway_refund_id"));
        assertEquals("late_payment", refundRow(p.orderId()).get("refund_reason"), "причина первой команды не меняется");
    }

    @Tag("TC-522")
    @Test
    void aTemporaryFailureOfTheGatewayIsRepeatedByTheScheduleWithTheSameKey() throws Exception {
        // Одна попытка клиента это до трёх запросов: три отказа подряд провалят первую попытку, вторая по расписанию проходит
        refundMode("instant", 3);
        Opened p = confirmed();
        UUID paymentId = paymentId(p.orderId());

        requestRefund(p, "seller_api_timeout");

        assertTrue(await(LIMIT, () -> "refunded".equals(status(p.orderId()))), "повторная попытка не вернула деньги");
        List<Map<String, Object>> attempts = attempts(paymentId);
        assertEquals(2, attempts.size());
        assertEquals("failed", attempts.get(0).get("status"));
        assertEquals("gateway_unavailable", attempts.get(0).get("error_code"));
        assertEquals("succeeded", attempts.get(1).get("status"));
        assertEquals(1, published(p.orderId().toString(), "payment.refunded").size());
        assertEquals(0, published(p.orderId().toString(), "payment.refund-escalated").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-523

    @Tag("TC-523")
    @Test
    void aRefundAcceptedForProcessingWaitsForTheSignedNotificationAndThenCompletes() throws Exception {
        refundMode("pending", 0);
        Opened p = confirmed();
        UUID paymentId = paymentId(p.orderId());
        requestRefund(p, "late_payment");

        assertTrue(await(LIMIT, () -> !attempts(paymentId).isEmpty() && "processing".equals(attempts(paymentId).get(0).get("status"))),
                "шлюз не принял возврат");
        assertEquals("confirmed", status(p.orderId()), "до подтверждения деньги не считаются возвращёнными");
        String refundId = (String) refundRow(p.orderId()).get("gateway_refund_id");
        assertNotNull(refundId);
        assertEquals(0, published(p.orderId().toString(), "payment.refunded").size());

        Map<String, Object> body = notification("refund.completed", "evt_" + UUID.randomUUID(), p, AMOUNT, "RUB");
        dataOf(body).put("refundId", refundId);
        Response first = send(body);
        Response again = send(body);

        assertEquals(200, first.status(), first.body());
        assertEquals(200, again.status(), again.body());
        assertEquals("refunded", status(p.orderId()));
        assertEquals("succeeded", attempts(paymentId).get(0).get("status"));
        List<Map<String, Object>> events = published(p.orderId().toString(), "payment.refunded");
        assertEquals(1, events.size(), "повтор уведомления второго события не рождает");
        assertEquals(false, payloadOf(events.get(0)).get("manual"));
        List<Map<String, Object>> processed = eventsOf(p.gatewayId());
        assertEquals("refund.completed", processed.get(processed.size() - 1).get("event_type"));
        assertEquals("applied", processed.get(processed.size() - 1).get("outcome"));
    }

    @Tag("TC-523")
    @Test
    void aLostRefundNotificationIsFoundByTheReconciliation() throws Exception {
        refundMode("pending", 0);
        Opened p = confirmed();
        UUID paymentId = paymentId(p.orderId());
        requestRefund(p, "late_payment");
        assertTrue(await(LIMIT, () -> !attempts(paymentId).isEmpty() && "processing".equals(attempts(paymentId).get(0).get("status"))));
        stubs.completeRefund(p.gatewayId(), Map.of("send", false));

        int confirmed = reconciler.runOnce();

        assertTrue(confirmed >= 1, "сверка не подтвердила возврат");
        assertEquals("refunded", status(p.orderId()));
        assertEquals("succeeded", attempts(paymentId).get(0).get("status"));
        List<Map<String, Object>> events = eventsOf(p.gatewayId());
        assertTrue(events.stream().anyMatch(e -> "reconcile.refunded".equals(e.get("event_type")) && "applied".equals(e.get("outcome"))), events.toString());
        assertEquals(1, published(p.orderId().toString(), "payment.refunded").size());
        reconciler.runOnce();
        assertEquals(1, published(p.orderId().toString(), "payment.refunded").size(), "повторный проход второго события не рождает");
    }

    // ------------------------------------------------------------------------------------------------ TC-524

    @Tag("TC-524")
    @Test
    void fiveRefusalsOfTheGatewayHandTheRefundToTheAdministratorWithOneEvent() throws Exception {
        Opened p = escalated();
        UUID paymentId = paymentId(p.orderId());

        List<Map<String, Object>> attempts = attempts(paymentId);
        assertEquals(5, attempts.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, attempts.get(i).get("attempt_no"));
            assertEquals("failed", attempts.get(i).get("status"), "попытка " + (i + 1));
            assertEquals("gateway_rejected_422", attempts.get(i).get("error_code"));
        }
        Map<String, Object> row = refundRow(p.orderId());
        assertEquals("confirmed", row.get("status"), "деньги не возвращены");
        assertEquals("late_payment", row.get("refund_reason"));
        assertNotNull(row.get("refund_escalated_at"));
        assertNull(row.get("gateway_refund_id"));
        assertEquals("paid", gatewayPayment(p).get("status"), "шлюз возврата не сделал");

        List<Map<String, Object>> events = published(p.orderId().toString(), "payment.refund-escalated");
        assertEquals(1, events.size());
        Map<String, Object> data = payloadOf(events.get(0));
        assertEquals(paymentId.toString(), data.get("paymentId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals(5, data.get("attempts"));
        assertEquals(Map.of("amount", (int) AMOUNT, "currency", "RUB"), data.get("amount"));
        assertEquals(0, published(p.orderId().toString(), "payment.refunded").size());

        UUID again = requestRefund(p, "late_payment");
        assertTrue(await(LIMIT, () -> processed(again)));
        assertEquals(5, attempts(paymentId).size(), "команда после передачи администратору новых попыток не создаёт");
    }

    // ------------------------------------------------------------------------------------------------ TC-525, TC-526

    @Tag("TC-525")
    @Tag("TC-526")
    @Test
    void theQueueShowsTheRefundAndTheMarkRefundsThePaymentWithOneEventAndAnAuditRecord() throws Exception {
        Opened p = escalated();
        UUID paymentId = paymentId(p.orderId());

        Response list = gatewayCaller.get(MANUAL + "?limit=100", adminAuth());
        assertEquals(200, list.status(), list.body());
        assertEquals("no-store", list.header("Cache-Control"));
        Map<?, ?> row = ((List<?>) list.json().get("items")).stream().map(i -> (Map<?, ?>) i).filter(i -> paymentId.toString().equals(i.get("paymentId")))
                .findFirst().orElseThrow(() -> new AssertionError("в очереди нет платежа " + paymentId + ": " + list.body()));
        assertEquals(Set.of("paymentId", "orderId", "amount", "refundReason", "attempts", "escalatedAt"), row.keySet());
        assertEquals(p.orderId().toString(), row.get("orderId"));
        assertEquals("late_payment", row.get("refundReason"));
        assertEquals(5, row.get("attempts"));
        assertEquals(Map.of("amount", (int) AMOUNT, "currency", "RUB"), row.get("amount"));

        String key = key();
        String body = "{\"externalRefundId\":\"manual-" + paymentId + "\"}";
        Response done = complete(paymentId, body, key);

        assertEquals(200, done.status(), done.body());
        assertEquals("refunded", done.json().get("paymentStatus"));
        assertEquals(paymentId.toString(), done.json().get("paymentId"));
        assertEquals(p.orderId().toString(), done.json().get("orderId"));
        assertNotNull(done.json().get("refundedAt"));
        Map<String, Object> after = refundRow(p.orderId());
        assertEquals("refunded", after.get("status"));
        assertFalse((Boolean) after.get("in_admin_queue"));
        assertEquals("manual-" + paymentId, after.get("gateway_refund_id"));
        assertNotNull(after.get("refunded_at"));
        assertEquals(5, attempts(paymentId).size());

        List<Map<String, Object>> refunded = published(p.orderId().toString(), "payment.refunded");
        assertEquals(1, refunded.size());
        assertEquals(true, payloadOf(refunded.get(0)).get("manual"));
        assertEquals("late_payment", payloadOf(refunded.get(0)).get("refundReason"));

        List<Map<String, Object>> audit = published(paymentId.toString(), "audit.recorded");
        assertEquals(1, audit.size());
        assertEquals("audit.events", audit.get(0).get("topic"));
        Map<String, Object> record = payloadOf(audit.get(0));
        assertEquals("refund.marked_manual", record.get("action"));
        assertEquals("admin", record.get("actorRole"));
        assertEquals(ADMIN.toString(), record.get("actorId"));
        assertEquals("payment", record.get("objectType"));
        assertEquals(paymentId.toString(), record.get("objectId"));
        assertEquals(Set.of("occurredAt", "actorId", "actorRole", "action", "objectType", "objectId", "changes", "actorIp", "actorIpHashed", "correlationId"),
                record.keySet(), "в записи аудита нет персональных данных");

        Response replay = complete(paymentId, body, key);
        assertEquals(200, replay.status(), replay.body());
        assertEquals("true", replay.header("Idempotency-Replayed"));
        assertEquals(done.json(), replay.json(), "повтор возвращает тот же ответ (порядок полей в сохранённом ответе другой)");

        Response second = complete(paymentId, body, key());
        assertEquals(409, second.status(), second.body());
        assertEquals("refund-not-allowed", code(second));

        assertEquals(1, published(p.orderId().toString(), "payment.refunded").size(), "повтор отметки второго события не рождает");
        assertEquals(1, published(paymentId.toString(), "audit.recorded").size(), "и второй записи аудита");
        Response listAfter = gatewayCaller.get(MANUAL + "?limit=100", adminAuth());
        assertFalse(listAfter.body().contains(paymentId.toString()), "отмеченный возврат остался в очереди");
    }

    @Tag("TC-526")
    @Test
    void aRefundNotWaitingForAnAdministratorOrUnknownCannotBeMarked() throws Exception {
        Opened p = confirmed();
        UUID paymentId = paymentId(p.orderId());

        Response notQueued = complete(paymentId, null, key());
        assertEquals(409, notQueued.status(), notQueued.body());
        assertEquals("refund-not-allowed", code(notQueued));
        assertEquals("confirmed", status(p.orderId()));
        assertEquals(0, published(paymentId.toString(), "audit.recorded").size());

        Response unknown = complete(UUID.randomUUID(), null, key());
        assertEquals(404, unknown.status(), unknown.body());
        Response malformed = gatewayCaller.post(MANUAL + "/not-a-uuid/complete", null, with(adminAuth(), "Idempotency-Key", key()));
        assertTrue(malformed.status() == 400 || malformed.status() == 404, malformed.body());
    }

    // ------------------------------------------------------------------------------------------------ TC-527

    private record Operation(String name, String method, String path) {
    }

    @Tag("TC-527")
    @Test
    void bothOperationsRefuseCallersWithoutTheRightAndOnlyAnAdministratorWithASecondFactorPasses() throws Exception {
        String some = UUID.randomUUID().toString();
        List<Operation> operations = List.of(new Operation("listManualRefunds", "GET", MANUAL),
                new Operation("completeManualRefund", "POST", MANUAL + "/" + some + "/complete"));
        String noScope = TOKENS.token().subject(ADMIN.toString()).scopes("seller.catalog").roles("admin").amr("pwd", "otp").build();
        String buyer = TOKENS.token().scopes("staff.admin").roles("buyer").amr("pwd").build();
        String seller = TOKENS.token().scopes("staff.admin").roles("seller").amr("pwd", "otp").build();
        String moderator = TOKENS.token().scopes("staff.admin").roles("moderator").amr("pwd", "otp").build();
        String supportOperator = TOKENS.token().scopes("staff.admin").roles("support-operator").amr("pwd", "otp").build();
        String noSecondFactor = TOKENS.token().subject(ADMIN.toString()).scopes("staff.admin").roles("admin").amr("pwd").build();
        String smsOnly = TOKENS.token().subject(ADMIN.toString()).scopes("staff.admin").roles("admin").amr("sms").build();

        for (Operation operation : operations) {
            Response anonymous = call(operation, new String[0]);
            assertEquals(401, anonymous.status(), operation.name() + ": без токена");
            assertEquals("unauthenticated", code(anonymous), operation.name());
            assertEquals(403, call(operation, StandHttp.bearer(noScope)).status(), operation.name() + ": чужая область");
            for (String wrongRole : List.of(buyer, seller, moderator, supportOperator)) {
                Response response = call(operation, StandHttp.bearer(wrongRole));
                assertEquals(403, response.status(), operation.name() + ": чужая роль");
                assertEquals("forbidden", code(response), operation.name());
            }
            Response noOtp = call(operation, StandHttp.bearer(noSecondFactor));
            assertEquals(403, noOtp.status(), operation.name() + ": без второго фактора");
            assertEquals("second-factor-required", code(noOtp), operation.name());
            Response sms = call(operation, StandHttp.bearer(smsOnly));
            assertEquals(403, sms.status(), operation.name() + ": сессия по SMS");
            assertNotNull(code(sms), operation.name());
        }

        assertEquals(200, call(operations.get(0), adminAuth()).status());
        assertEquals(404, call(operations.get(1), adminAuth()).status(), "администратору неизвестный платёж отвечает 404, а не отказом в доступе");
    }

    @Tag("TC-527")
    @Test
    void theQueueRefusesBrokenParametersAndTheBodyOfTheMarkIsChecked() throws Exception {
        for (String query : new String[] {"?limit=0", "?limit=101", "?limit=abc", "?cursor=not-a-cursor", "?status=all"}) {
            Response response = gatewayCaller.get(MANUAL + query, adminAuth());
            assertEquals(422, response.status(), query + ": " + response.body());
            assertEquals("validation-failed", code(response), query);
        }
        UUID some = UUID.randomUUID();
        for (String body : new String[] {"{\"externalRefundId\":5}", "{\"externalRefundId\":\"\"}", "{\"extra\":true}"}) {
            Response response = complete(some, body, key());
            assertEquals(422, response.status(), body + ": " + response.body());
        }
        Response notJson = gatewayCaller.post(MANUAL + "/" + some + "/complete", "{", with(adminAuth(), "Idempotency-Key", key()));
        assertEquals(400, notJson.status(), notJson.body());
    }

    private static Response call(Operation operation, String[] auth) throws java.io.IOException {
        if (operation.method().equals("GET")) {
            return gatewayCaller.get(operation.path(), auth);
        }
        return gatewayCaller.post(operation.path(), null, with(auth, "Idempotency-Key", key()));
    }
}
