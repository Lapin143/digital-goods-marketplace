package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Concurrent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Повторный запрос возврата и гонки вокруг него (INV-15, ADR-007, FT-6.3, FT-6.4): шесть одинаковых команд подряд дают один возврат;
 * пять одновременных отметок администратора одного возврата дают один успех, четыре 409, одно событие и одну запись аудита; подтверждение
 * шлюза и отметка администратора, пришедшие вместе, дают ровно одно событие {@code payment.refunded}. Строку платежа держит {@code FOR UPDATE},
 * проигравший находит итог победителя. Доказательство это запросы к базе. Задание CI {@code concurrency} повторяет тесты с тегом
 * {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class RefundConcurrencyIT extends RefundStandIT {

    private static final int COMMANDS = 6;
    private static final int MARKS = 5;
    /** Участников не больше пула соединений сервиса (7): каждый ждёт блокировку строки, держа соединение. */
    private static final int MIXED = 6;

    @Tag("TC-038")
    @Test
    void sixIdenticalRefundCommandsCreateOneRefundAndOneEvent() throws Exception {
        refundMode("instant", 0);
        Opened p = confirmed();
        UUID paymentId = paymentId(p.orderId());

        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < COMMANDS; i++) {
            commands.add(requestRefund(p, i % 2 == 0 ? "late_payment" : "dispute_decision"));
        }

        assertTrue(await(LIMIT, () -> commands.stream().allMatch(RefundStandIT::processed)), "команды не обработаны");
        assertTrue(await(LIMIT, () -> "refunded".equals(status(p.orderId()))));
        List<Map<String, Object>> attempts = attempts(paymentId);
        assertEquals(1, attempts.size(), "попытка одна: команда в обработке второй не создаёт");
        assertEquals("succeeded", attempts.get(0).get("status"));
        assertEquals(1, published(p.orderId().toString(), "payment.refunded").size());
        assertEquals("late_payment", refundRow(p.orderId()).get("refund_reason"), "причина первой команды");
        assertEquals(1, db.queryForObject("select count(*) from payments.payment where order_id = ?", Integer.class, p.orderId()));
        assertEquals(1, db.queryForObject("select count(distinct gateway_refund_id) from payments.payment where order_id = ? and gateway_refund_id is not null",
                Integer.class, p.orderId()));
    }

    @Tag("TC-038")
    @Test
    void fiveParallelMarksOfTheAdministratorGiveOneSuccessFourConflictsOneEventAndOneAuditRecord() throws Exception {
        Opened p = escalated();
        UUID paymentId = paymentId(p.orderId());
        String body = "{\"externalRefundId\":\"manual-" + paymentId + "\"}";

        List<Response> responses = Concurrent.race(MARKS, call -> complete(paymentId, body, key()));

        long ok = responses.stream().filter(r -> r.status() == 200).count();
        long conflicts = responses.stream().filter(r -> r.status() == 409).count();
        assertEquals(1, ok, responses.stream().map(Response::body).toList().toString());
        assertEquals(MARKS - 1, conflicts, responses.stream().map(Response::body).toList().toString());
        for (Response response : responses) {
            if (response.status() == 409) {
                assertEquals("refund-not-allowed", code(response));
            }
        }
        assertEquals("refunded", status(p.orderId()));
        assertEquals(1, published(p.orderId().toString(), "payment.refunded").size());
        assertEquals(1, published(paymentId.toString(), "audit.recorded").size());
        assertEquals(true, payloadOf(published(p.orderId().toString(), "payment.refunded").get(0)).get("manual"));
        assertEquals(5, attempts(paymentId).size(), "после отметки новых попыток нет");
    }

    @Tag("TC-038")
    @Test
    void theNotificationOfTheGatewayAndTheMarkOfTheAdministratorArrivingTogetherGiveOneRefundedEvent() throws Exception {
        Opened p = escalated();
        UUID paymentId = paymentId(p.orderId());
        Map<String, Object> notice = notification("refund.completed", "evt_" + UUID.randomUUID(), p, AMOUNT, "RUB");
        dataOf(notice).put("refundId", "ref_" + UUID.randomUUID());
        String webhook = Json.write(notice);

        List<Response> responses = Concurrent.race(MIXED, call -> call % 2 == 0 ? send(webhook) : complete(paymentId, null, key()));

        for (int call = 0; call < MIXED; call++) {
            Response response = responses.get(call);
            if (call % 2 == 0) {
                assertEquals(200, response.status(), "уведомление шлюза всегда получает 200: " + response.body());
            } else {
                assertTrue(response.status() == 200 || response.status() == 409, "отметка: " + response.status() + " " + response.body());
            }
        }
        assertEquals("refunded", status(p.orderId()));
        List<Map<String, Object>> refunded = published(p.orderId().toString(), "payment.refunded");
        assertEquals(1, refunded.size(), "ровно одно событие payment.refunded");
        boolean manual = Boolean.TRUE.equals(payloadOf(refunded.get(0)).get("manual"));
        List<Map<String, Object>> audit = published(paymentId.toString(), "audit.recorded");
        assertEquals(manual ? 1 : 0, audit.size(), "запись аудита есть тогда и только тогда, когда выиграла отметка администратора");
        long successfulMarks = java.util.stream.IntStream.range(0, MIXED).filter(call -> call % 2 == 1 && responses.get(call).status() == 200).count();
        assertEquals(manual ? 1 : 0, successfulMarks, "успешная отметка одна, если деньги отметил администратор, и ни одной, если подтвердил шлюз");
        assertEquals(1, db.queryForObject("select count(*) from payments.payment where order_id = ? and status = 'refunded'", Integer.class, p.orderId()));
    }
}
