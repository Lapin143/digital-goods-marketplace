package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.Concurrent;
import dgm.order.Neighbours.ConfirmMode;
import dgm.order.orders.service.PaymentDecision;
import dgm.order.orders.service.SagaOrchestrator;
import dgm.order.orders.service.SagaOutcome;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Гонки событий заказа (ADR-004, ADR-013, TC-033): подтверждение оплаты и истечение резерва приходят одновременно и в любом порядке, одно
 * подтверждение приходит несколько раз сразу. Платёж не теряется (ключи есть: заказ «оплачен», при отмене раньше это поздняя оплата), без
 * ключей остаётся одна отмена и одна команда возврата. Доказательство это строка заказа и Outbox. Задание CI {@code concurrency} повторяет тесты
 * с тегом {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class OrderPaymentRaceIT extends OrderStandIT {

    private static final long PRICE = 99_900;
    private static final int ROUNDS = 8;

    private final UUID seller = UUID.randomUUID();

    @Autowired
    private SagaOrchestrator saga;

    /** Заказ «ожидает оплаты» нового покупателя: лимит неоплаченных заказов одного покупателя не мешает. */
    private UUID place() throws IOException {
        UUID product = NEIGHBOURS.product(seller, "Игра для гонки оплаты", PRICE, 20);
        Response response = create(buyerToken(UUID.randomUUID()), product, 1);
        assertEquals(201, response.status(), response.body());
        return UUID.fromString((String) response.json().get("orderId"));
    }

    private List<SagaOutcome.Kind> paymentAgainstExpiry(UUID orderId) {
        return Concurrent.race(2, i -> i == 0
                ? saga.applyPayment(orderId, saga.decidePayment(orderId), Instant.now()).kind()
                : saga.reservationExpired(orderId).kind());
    }

    @Tag("TC-033")
    @Test
    void confirmationAndExpiryAtTheSameTimeWithKeysNeverLoseThePayment() throws Exception {
        NEIGHBOURS.confirmIs(ConfirmMode.OK);
        for (int round = 1; round <= ROUNDS; round++) {
            UUID orderId = place();
            String id = orderId.toString();

            paymentAgainstExpiry(orderId);

            assertEquals("paid", status(id), "раунд " + round + ": оплата не потеряна");
            List<Map<String, Object>> paid = events(id, "order.paid");
            assertEquals(1, paid.size(), "раунд " + round + ": одно событие order.paid");
            int cancels = events(id, "order.cancelled").size();
            assertTrue(cancels <= 1, "раунд " + round + ": отмена не больше одной");
            boolean isLate = Boolean.TRUE.equals(data(paid.get(0)).get("latePayment"));
            assertEquals(cancels == 1, isLate, "раунд " + round + ": поздняя оплата отмечена тогда и только тогда, когда отмена успела раньше");
            assertEquals(0, events(id, "order.refund-requested").size(), "раунд " + round + ": ключи есть, возврата нет");
        }
    }

    @Tag("TC-033")
    @Test
    void confirmationAndExpiryAtTheSameTimeWithoutKeysLeaveOneCancelAndOneRefundCommand() throws Exception {
        NEIGHBOURS.confirmIs(ConfirmMode.NO_KEYS, 0);
        for (int round = 1; round <= ROUNDS; round++) {
            UUID orderId = place();
            String id = orderId.toString();

            paymentAgainstExpiry(orderId);

            assertEquals("cancelled", status(id), "раунд " + round);
            assertEquals("reservation_expired", order(id).get("cancel_reason"));
            assertEquals(1, events(id, "order.cancelled").size(), "раунд " + round + ": одна отмена");
            assertEquals(1, events(id, "order.refund-requested").size(), "раунд " + round + ": одна команда возврата");
            assertEquals(0, events(id, "order.paid").size());
        }
    }

    @Tag("TC-033")
    @Test
    void severalAppliedConfirmationsAtTheSameTimeGiveOneTransitionAndOneEvent() throws Exception {
        NEIGHBOURS.confirmIs(ConfirmMode.OK);
        for (int round = 1; round <= ROUNDS; round++) {
            UUID orderId = place();
            String id = orderId.toString();

            List<SagaOutcome.Kind> results = Concurrent.race(4, i -> saga.applyPayment(orderId, new PaymentDecision.Pay(), Instant.now()).kind());

            assertEquals(1, results.stream().filter(SagaOutcome.Kind.APPLIED::equals).count(), "раунд " + round + ": перевод выполнил один участник");
            assertEquals("paid", status(id));
            assertEquals(1, events(id, "order.paid").size());
        }
    }
}
