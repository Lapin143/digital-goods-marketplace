package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Concurrent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Одно уведомление шлюза приходит много раз (NFT-2.3, INV-14, ADR-006): пять одинаковых вебхуков одновременно и пять по очереди дают один
 * переход платежа, одну запись {@code payment_event} и одно событие в Outbox. Строку платежа держит {@code FOR UPDATE}, повтор находит
 * запись первого. Доказательство это запросы к базе, а не только ответы 200. Задание CI {@code concurrency} повторяет тесты с тегом
 * {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class PaymentWebhookConcurrencyIT extends PaymentWebhookStandIT {

    private static final int PARALLEL = 5;
    private static final int SEQUENTIAL = 5;
    /** Участников не больше пула соединений сервиса (7): каждый ждёт блокировку строки, держа соединение. */
    private static final int MIXED = 6;

    @Tag("TC-032")
    @Test
    void fiveParallelAndFiveSequentialIdenticalNoticesGiveOneTransitionOneRecordAndOneEvent() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        String body = Json.write(paid(p));

        List<Response> parallel = Concurrent.race(PARALLEL, call -> send(body));
        for (Response response : parallel) {
            assertEquals(200, response.status(), response.body());
        }
        for (int i = 0; i < SEQUENTIAL; i++) {
            Response response = send(body);
            assertEquals(200, response.status(), response.body());
        }

        assertEquals("confirmed", status(p.orderId()));
        List<Map<String, Object>> events = eventsOf(p.gatewayId());
        assertEquals(1, events.size(), "одна запись payment_event на пару «платёж, тип» (INV-14)");
        assertEquals("applied", events.get(0).get("outcome"));
        assertEquals(1, outbox(p.orderId(), "payment.confirmed").size(), "одно событие payment.confirmed в Outbox");
        assertEquals(1, outboxCount(p.orderId()));
        assertEquals(1, db.queryForObject("select count(*) from payments.payment where order_id = ?", Integer.class, p.orderId()));
        assertEquals(0, db.queryForObject("select count(*) from payments.payment_event where payment_id = ? and outcome <> 'applied'", Integer.class,
                payment(p.orderId()).get("id")));
    }

    @Tag("TC-032")
    @Test
    void aPaidAndADeclinedNoticeRacingForOnePaymentLeaveExactlyOneOutcome() throws Exception {
        Opened p = opened();
        gatewayPays(p);
        String paidBody = Json.write(paid(p));
        String declinedBody = Json.write(notification("payment.declined", "evt_" + UUID.randomUUID(), p, 299800, "RUB"));

        List<Response> responses = Concurrent.race(MIXED, call -> call % 2 == 0 ? send(paidBody) : send(declinedBody));
        for (Response response : responses) {
            assertEquals(200, response.status(), response.body());
        }

        String finalStatus = status(p.orderId());
        assertTrue(Set.of("confirmed", "rejected").contains(finalStatus), finalStatus);
        assertEquals(1, outboxCount(p.orderId()), "ровно одно из событий payment.confirmed и payment.rejected");
        assertEquals("confirmed".equals(finalStatus) ? 1 : 0, outbox(p.orderId(), "payment.confirmed").size());
        assertEquals("rejected".equals(finalStatus) ? 1 : 0, outbox(p.orderId(), "payment.rejected").size());
        Set<String> outcomes = eventsOf(p.gatewayId()).stream().map(e -> (String) e.get("outcome")).collect(Collectors.toSet());
        assertEquals(Set.of("applied", "anomaly"), outcomes, "проигравшее уведомление записано как аномалия, не как второй исход");
        assertEquals(2, eventsOf(p.gatewayId()).size());
    }
}
