package dgm.order.orders.service;

import static dgm.order.orders.FakeOrders.id;
import static dgm.order.orders.FakeOrders.order;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.order.orders.FakeOrders;
import dgm.order.orders.domain.OrderSession;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Карточка заказа и его платёжная сессия: владелец из токена, чужой заказ неотличим от несуществующего (U1), сессия отдаётся только
 * заказу в статусе «ожидает оплаты» и пока она не кончилась, иначе 409 со статусом и причиной отмены.
 */
@Tag("TC-514")
class OrderViewServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-18T12:00:00.123Z");
    private static final UUID ALICE = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID BOB = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");
    private static final String URL = "https://localhost:8443/payment/pay/pay_1";

    private ManualClock clock;
    private OrderViewService service;

    private static OrderSession session(int n, String status, String reason, String url, Instant expiresAt) {
        return new OrderSession(id(n), status, Optional.ofNullable(reason), Optional.ofNullable(url), Optional.ofNullable(expiresAt));
    }

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        FakeOrders orders = new FakeOrders()
                .with(ALICE, order(1, "awaiting_payment", T0), order(2, "paid", T0), order(3, "cancelled", T0), order(4, "awaiting_payment", T0),
                        order(5, "created", T0))
                .withSession(ALICE, session(1, "awaiting_payment", null, URL, T0.plusSeconds(720)))
                .withSession(ALICE, session(2, "paid", null, null, null))
                .withSession(ALICE, session(3, "cancelled", "payment_declined", URL, T0.plusSeconds(720)))
                .withSession(ALICE, session(4, "awaiting_payment", null, URL, T0.plusSeconds(10)))
                .withSession(ALICE, session(5, "created", null, null, null))
                .with(BOB, order(6, "awaiting_payment", T0))
                .withSession(BOB, session(6, "awaiting_payment", null, URL, T0.plusSeconds(720)));
        service = new OrderViewService(orders, clock);
    }

    @Test
    void theOwnerGetsTheOrder() {
        assertEquals(id(2), service.get(id(2), ALICE).id());
        assertEquals("paid", service.get(id(2), ALICE).status());
    }

    @Test
    void someoneElsesOrderAndAnUnknownOneAreBothNotFound() {
        ProblemException foreign = assertThrows(ProblemException.class, () -> service.get(id(6), ALICE));
        ProblemException unknown = assertThrows(ProblemException.class, () -> service.get(id(99), ALICE));

        assertEquals(ProblemType.NOT_FOUND, foreign.type());
        assertEquals(ProblemType.NOT_FOUND, unknown.type());
        assertEquals(foreign.getMessage(), unknown.getMessage(), "ответы неотличимы (U1)");
    }

    @Test
    void anOrderAwaitingPaymentGivesItsSession() {
        OrderSession session = service.paymentSession(id(1), ALICE);

        assertEquals(id(1), session.orderId());
        assertEquals(URL, session.url().orElseThrow());
        assertEquals(T0.plusSeconds(720), session.expiresAt().orElseThrow());
    }

    @Test
    void theSessionOfSomeoneElsesOrAnUnknownOrderIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> service.paymentSession(id(6), ALICE)).type());
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> service.paymentSession(id(99), ALICE)).type());
    }

    @Test
    void anOrderThatIsNotAwaitingPaymentGivesTheStateConflictWithTheStatusNotTheLink() {
        for (int n : new int[] {2, 5}) {
            ProblemException e = assertThrows(ProblemException.class, () -> service.paymentSession(id(n), ALICE));
            assertEquals(ProblemType.STATE_CONFLICT, e.type());
            assertEquals(409, e.type().status());
            assertEquals(n == 2 ? "paid" : "created", e.extensions().get("currentStatus"));
            assertEquals(false, e.extensions().containsKey("cancelReason"));
            assertEquals(false, e.getMessage().contains(URL), "ссылка на оплату не отдаётся");
        }
    }

    @Test
    void aCancelledOrderGivesTheReasonButNotTheLink() {
        ProblemException e = assertThrows(ProblemException.class, () -> service.paymentSession(id(3), ALICE));

        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("cancelled", e.extensions().get("currentStatus"));
        assertEquals("payment_declined", e.extensions().get("cancelReason"));
        assertEquals(false, e.extensions().toString().contains(URL));
    }

    @Test
    void whenTheSessionHasEndedTheLinkIsNotGivenEvenIfTheStatusIsStillAwaitingPayment() {
        service.paymentSession(id(4), ALICE);
        clock.advance(java.time.Duration.ofSeconds(10));

        ProblemException e = assertThrows(ProblemException.class, () -> service.paymentSession(id(4), ALICE));

        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("awaiting_payment", e.extensions().get("currentStatus"));
    }
}
