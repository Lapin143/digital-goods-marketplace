package dgm.payment.payments.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import dgm.payment.payments.domain.Payment;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Открытие платёжной сессии (SEQ-01 шаги 12 и 13, ADR-015, INV-07, INV-11): порядок «строка, шлюз, запись сессии», вызов шлюза вне
 * транзакции, повтор по тому же заказу, отказ шлюза и продолжение после него, платёж в другом статусе. Настоящие SQL и гонки проверяют
 * интеграционные тесты.
 */
class PaymentSessionServiceTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final Instant START = Instant.parse("2026-10-18T12:00:00Z");
    private static final SessionSettings SETTINGS = new SessionSettings(Duration.ofMinutes(15), Duration.ofMinutes(12), Duration.ofMinutes(1),
            "https://localhost:8443");

    private FakePayments fake;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private PaymentSessionService service;
    private Instant sessionEnd;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        clock = new ManualClock(START);
        meters = new SimpleMeterRegistry();
        service = new PaymentSessionService(fake.payments, fake.transaction, fake.gateway, SETTINGS, clock, meters);
        sessionEnd = START.plus(Duration.ofMinutes(12));
    }

    private double count(String result) {
        return meters.counter("payment_sessions_total", "result", result).count();
    }

    private static ProblemException refused(Runnable call) {
        return assertThrows(ProblemException.class, call::run);
    }

    @Test
    void theSessionIsOpenedInThreeSteps() {
        Payment payment = service.open(ORDER, 299800, "RUB", sessionEnd);

        assertEquals(Payment.CREATED, payment.status());
        assertEquals(ORDER, payment.orderId());
        assertEquals(299800, payment.amount());
        assertEquals("pay_1", payment.gatewayPaymentId());
        assertEquals("https://localhost:8443/payment/pay/pay_1", payment.sessionUrl());
        assertEquals(sessionEnd, payment.sessionExpiresAt());
        assertEquals(2, fake.transaction.committed, "две короткие транзакции: строка и запись сессии");
        assertEquals(1, fake.gateway.calls.size());
        assertEquals(1.0, count("opened"), 0);
    }

    @Test
    void theGatewayGetsThePaymentIdAsTheIdempotencyKeyTheOrderAndTheReturnAddress() {
        Payment payment = service.open(ORDER, 299800, "RUB", sessionEnd);
        FakePayments.Gateway.Call call = fake.gateway.calls.get(0);
        assertEquals(payment.id(), call.paymentId(), "ключ идемпотентности шлюза это идентификатор платежа");
        assertEquals(ORDER, call.orderId());
        assertEquals(299800, call.amount());
        assertEquals("RUB", call.currency());
        assertEquals("https://localhost:8443/orders/" + ORDER, call.returnUrl());
        assertEquals(sessionEnd, call.expiresAt());
    }

    @Test
    void aRepeatedCallForTheSameOrderReturnsTheSameSessionWithoutTheGateway() {
        Payment first = service.open(ORDER, 299800, "RUB", sessionEnd);
        Payment again = service.open(ORDER, 299800, "RUB", sessionEnd);
        assertEquals(first, again);
        assertEquals(1, fake.gateway.calls.size(), "шлюз повторно не вызывается");
        assertEquals(1, fake.payments.byOrder.size(), "один платёж на заказ (INV-11)");
        assertEquals(1.0, count("replayed"), 0);
    }

    @Test
    void whenTheGatewayIsDownTheRowStaysWithoutAnExternalIdAndTheAnswerIs503() {
        fake.gateway.failNext = 1;
        ProblemException e = refused(() -> service.open(ORDER, 299800, "RUB", sessionEnd));
        assertEquals(ProblemType.PAYMENT_SESSION_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        Payment row = fake.payments.byOrder.get(ORDER);
        assertNotNull(row, "платёж «создан» записан до вызова шлюза");
        assertNull(row.gatewayPaymentId());
        assertNull(row.sessionUrl());
        assertEquals(Payment.CREATED, row.status());
        assertEquals(1.0, count("unavailable"), 0);
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void aRepeatedCallAfterTheFailureContinuesWithTheSamePaymentIdAsTheGatewayKey() {
        fake.gateway.failNext = 1;
        refused(() -> service.open(ORDER, 299800, "RUB", sessionEnd));
        UUID paymentId = fake.payments.byOrder.get(ORDER).id();

        Payment payment = service.open(ORDER, 299800, "RUB", sessionEnd);
        assertEquals(paymentId, payment.id(), "строка та же, второй платёж не заводится");
        assertEquals(2, fake.gateway.calls.size());
        assertEquals(paymentId, fake.gateway.calls.get(0).paymentId());
        assertEquals(paymentId, fake.gateway.calls.get(1).paymentId(), "повтор идёт с тем же ключом шлюза");
        assertEquals("pay_1", payment.gatewayPaymentId());
    }

    @Test
    void aLostGatewayAnswerThenARepeatGivesTheSameGatewayPayment() {
        fake.gateway.loseResponse = true;
        refused(() -> service.open(ORDER, 299800, "RUB", sessionEnd));
        assertEquals(1, fake.gateway.created.size(), "у шлюза платёж уже создан, ответ потерян");

        Payment payment = service.open(ORDER, 299800, "RUB", sessionEnd);
        assertEquals(1, fake.gateway.created.size(), "второго платежа у шлюза нет (ключ идемпотентности)");
        assertEquals("pay_1", payment.gatewayPaymentId());
    }

    @Test
    void aGatewayRefusalByMeaningIsAlsoAnUnavailableSessionButIsCountedApart() {
        fake.gateway.rejectNext = 1;
        ProblemException e = refused(() -> service.open(ORDER, 299800, "RUB", sessionEnd));
        assertEquals(ProblemType.PAYMENT_SESSION_UNAVAILABLE, e.type());
        assertEquals(1.0, count("rejected"), 0);
        assertEquals(0.0, count("unavailable"), 0);
    }

    @Test
    void thePaymentInAnotherStatusIs409AndTheGatewayIsNotCalled() {
        for (String status : new String[] {Payment.CONFIRMED, Payment.REJECTED, Payment.REFUNDED}) {
            FakePayments local = new FakePayments();
            UUID order = UUID.randomUUID();
            local.payments.put(new Payment(UUID.randomUUID(), order, "pay_9", 299800, "RUB", "https://x/pay", sessionEnd, status));
            PaymentSessionService s = new PaymentSessionService(local.payments, local.transaction, local.gateway, SETTINGS, clock, meters);
            ProblemException e = refused(() -> s.open(order, 299800, "RUB", sessionEnd));
            assertEquals(ProblemType.STATE_CONFLICT, e.type());
            assertEquals(status, e.extensions().get("currentStatus"));
            assertTrue(local.gateway.calls.isEmpty(), status);
        }
    }

    @Test
    void anotherAmountForTheSameOrderIs409() {
        service.open(ORDER, 299800, "RUB", sessionEnd);
        ProblemException e = refused(() -> service.open(ORDER, 299900, "RUB", sessionEnd));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(299800, fake.payments.byOrder.get(ORDER).amount(), "платёж не изменён");
        assertEquals(1, fake.gateway.calls.size());
    }

    @Test
    void aSessionThatEndedAlreadyOrOutlivesTheReservationIsRefusedBeforeAnythingIsWritten() {
        ProblemException past = refused(() -> service.open(ORDER, 299800, "RUB", START));
        assertEquals(ProblemType.VALIDATION_FAILED, past.type());
        ProblemException tooLong = refused(() -> service.open(ORDER, 299800, "RUB", START.plus(Duration.ofMinutes(15)).plusMillis(1)));
        assertEquals(ProblemType.VALIDATION_FAILED, tooLong.type());
        assertTrue(fake.payments.byOrder.isEmpty());
        assertTrue(fake.gateway.calls.isEmpty());
        assertEquals(0, fake.transaction.committed);
    }

    @Test
    void thePaymentWasConfirmedWhileTheSessionWasBeingWrittenTheStatusStaysAndTheAddressComesFromTheGateway() {
        // Вебхук подтвердил платёж между вызовом шлюза и записью сессии: запись сессии (статус «создан») ничего не меняет
        PaymentSessionService racing = new PaymentSessionService(fake.payments, fake.transaction, (paymentId, orderId, amount, currency, returnUrl, expiresAt) -> {
            Payment row = fake.payments.byOrder.get(orderId);
            fake.payments.put(new Payment(row.id(), orderId, "pay_77", amount, currency, null, null, Payment.CONFIRMED));
            return new dgm.payment.payments.gateway.GatewayPayment("pay_77", "https://localhost:8443/payment/pay/pay_77", expiresAt);
        }, SETTINGS, clock, meters);

        Payment result = racing.open(ORDER, 299800, "RUB", sessionEnd);
        assertEquals(Payment.CONFIRMED, result.status(), "статус платежа сессия не меняет");
        assertEquals("pay_77", result.gatewayPaymentId());
        assertEquals("https://localhost:8443/payment/pay/pay_77", result.sessionUrl());
        assertFalse(fake.payments.byOrder.get(ORDER).hasSession(), "в базе сессия не записана: платёж уже не «создан»");
    }
}
