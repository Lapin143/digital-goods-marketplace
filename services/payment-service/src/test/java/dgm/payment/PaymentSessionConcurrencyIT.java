package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.Concurrent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Гонки открытия платёжной сессии (ADR-006, ADR-015, INV-11, NFT-2.3): двадцать одновременных вызовов по одному заказу, с разными
 * ключами идемпотентности и с одним. Доказательство это запросы к базе и к заглушке шлюза (одна строка платежа, один платёж у шлюза),
 * а не только ответы потоков. Задание CI {@code concurrency} повторяет тесты с тегом {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class PaymentSessionConcurrencyIT extends PaymentStandIT {

    private static final int CALLS = 20;
    private static final long AMOUNT = 299800;

    @Tag("TC-034")
    @Test
    void twentyCallsForOneOrderWithDifferentKeysGiveOnePaymentRowAndOnePaymentAtTheGateway() throws Exception {
        UUID orderId = UUID.randomUUID();
        List<Response> responses = Concurrent.race(CALLS, call -> open(orderId, AMOUNT));

        Set<Object> paymentIds = new HashSet<>();
        Set<Object> sessions = new HashSet<>();
        for (Response response : responses) {
            assertEquals(201, response.status(), response.body());
            paymentIds.add(response.json().get("paymentId"));
            sessions.add(response.json().get("sessionUrl"));
        }
        assertEquals(1, paymentIds.size(), "все вызовы получили один и тот же платёж");
        assertEquals(1, sessions.size(), "и одну и ту же страницу оплаты");
        assertEquals(1, paymentsOf(orderId), "одна строка платежа на заказ (INV-11)");
        assertEquals(1, gatewayPaymentsOf(orderId).size(), "у шлюза один платёж: ключ идемпотентности равен идентификатору платежа");
        assertEquals(0, db.queryForObject("select count(*) from (select order_id from payments.payment where order_id = ? group by order_id having count(*) > 1) twice",
                Integer.class, orderId));
    }

    @Tag("TC-034")
    @Test
    void twentyCallsWithTheSameKeyAreServedOnceAndTheRestReplayOrWaitForTheFirst() throws Exception {
        UUID orderId = UUID.randomUUID();
        String key = key();
        List<Response> responses = Concurrent.race(CALLS, call -> open(orders, orderId, AMOUNT, key));

        int created = 0;
        for (Response response : responses) {
            if (response.status() == 201) {
                created++;
            } else {
                assertEquals(409, response.status(), response.body());
                assertEquals("request-in-progress", code(response));
            }
        }
        assertTrue(created >= 1, "первый вызов получил сессию");
        assertEquals(1, paymentsOf(orderId));
        assertEquals(1, gatewayPaymentsOf(orderId).size());

        Response after = open(orders, orderId, AMOUNT, key);
        assertEquals(201, after.status(), after.body());
        assertEquals("true", after.header("Idempotency-Replayed"), "после гонки тот же ключ отдаёт сохранённый ответ");
    }
}
