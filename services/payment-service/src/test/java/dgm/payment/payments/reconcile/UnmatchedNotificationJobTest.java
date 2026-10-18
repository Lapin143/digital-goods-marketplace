package dgm.payment.payments.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository.Unmatched;
import dgm.payment.payments.service.PaymentOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Несопоставленные уведомления: ждут платёж, применяются через сверку и дают оповещение, если ждут дольше часа. */
@Tag("TC-507")
class UnmatchedNotificationJobTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000401");
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final long AMOUNT = 299800;

    private FakePayments fake;
    private SimpleMeterRegistry meters;
    private ManualClock clock;
    private UnmatchedNotificationJob job;
    private PaymentReconciliation reconciliation;
    private PaymentOutcomes outcomes;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        meters = new SimpleMeterRegistry();
        clock = new ManualClock(NOW);
        outcomes = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, clock, meters);
        reconciliation = new PaymentReconciliation(fake.gateway, outcomes, clock, meters);
        job = new UnmatchedNotificationJob(fake.unmatched, fake.payments, reconciliation, outcomes, ReconcileSettings.defaults(), clock, meters);
    }

    private Unmatched waiting(String gatewayId, Instant receivedAt) {
        Unmatched n = new Unmatched(UUID.randomUUID(), gatewayId, "payment.paid", "evt_1", ORDER, NOW.minusSeconds(5), receivedAt);
        fake.unmatched.insertIfAbsent(n);
        return n;
    }

    private void createPayment(String gatewayId) {
        fake.payments.putCreated(new Payment(PAYMENT, ORDER, gatewayId, AMOUNT, "RUB", gatewayId == null ? null : "https://x/pay", NOW.plusSeconds(60),
                Payment.CREATED), NOW.minusSeconds(30));
    }

    private double count(String name, String result) {
        var counter = meters.find(name).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aNotificationWaitsWhileThePaymentDoesNotExist() {
        Unmatched n = waiting("pay_1", NOW);

        assertEquals(0, job.runPass());

        assertNull(fake.unmatched.items.get(n.id()).matchedAt);
    }

    @Test
    void whenThePaymentAppearsTheNotificationIsAppliedThroughTheGatewayStatus() {
        Unmatched n = waiting("pay_1", NOW);
        job.runPass();
        createPayment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");

        assertEquals(1, job.runPass());

        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(NOW, fake.unmatched.items.get(n.id()).matchedAt);
        assertEquals(1, count("payment_unmatched_total", "matched"));
        assertEquals(1, fake.transaction.events.size());
    }

    @Test
    void aPaymentWithoutAGatewayIdAdoptsTheOneFromTheNotification() {
        waiting("pay_1", NOW);
        createPayment(null);
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");

        assertEquals(1, job.runPass());

        Payment p = fake.payments.byOrder.get(ORDER);
        assertEquals("pay_1", p.gatewayPaymentId());
        assertEquals(Payment.CONFIRMED, p.status());
    }

    @Test
    void aNotificationNamingAnotherGatewayPaymentThanTheOrderHasIsClosedAsAnAnomaly() {
        Unmatched n = waiting("pay_other", NOW);
        createPayment("pay_1");

        assertEquals(1, job.runPass());

        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());
        assertEquals(PaymentEventRepository.ANOMALY, fake.events.row("pay_other", "payment.paid").outcome());
        assertNotNull(fake.unmatched.items.get(n.id()).matchedAt);
        assertEquals(1, count("payment_unmatched_total", "mismatch"));
        assertTrue(fake.transaction.events.isEmpty());
    }

    @Test
    void whenTheGatewayDoesNotAnswerTheNotificationStaysForTheNextPass() {
        Unmatched n = waiting("pay_1", NOW);
        createPayment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");
        fake.gateway.statusFailures = 1;

        assertEquals(0, job.runPass());
        assertNull(fake.unmatched.items.get(n.id()).matchedAt);
        assertEquals(Payment.CREATED, fake.payments.byOrder.get(ORDER).status());

        assertEquals(1, job.runPass());
        assertEquals(Payment.CONFIRMED, fake.payments.byOrder.get(ORDER).status());
    }

    @Test
    void aNotificationThatWaitsMoreThanAnHourRaisesOneAlert() {
        Unmatched n = waiting("pay_1", NOW.minus(Duration.ofMinutes(61)));
        waiting("pay_2", NOW.minus(Duration.ofMinutes(10)));

        job.runPass();
        job.runPass();

        assertEquals(1.0, meters.get("payment_unmatched_alerts_total").counter().count());
        assertEquals(NOW, fake.unmatched.items.get(n.id()).alertedAt);
    }

    @Test
    void anAlertedNotificationIsStillMatchedWhenThePaymentFinallyAppears() {
        Unmatched n = waiting("pay_1", NOW.minus(Duration.ofMinutes(90)));
        job.runPass();
        createPayment("pay_1");
        fake.gateway.know("pay_1", GatewayStatus.PAID, AMOUNT, "RUB");

        assertEquals(1, job.runPass());

        assertNotNull(fake.unmatched.items.get(n.id()).matchedAt);
        assertNull(fake.unmatched.items.get(n.id()).alertedAt, "сопоставленное уведомление оповещения не несёт, как требует ограничение базы");
        assertEquals(1.0, meters.get("payment_unmatched_alerts_total").counter().count());
    }

    @Test
    void aFailureOfAPassIsSwallowedByTheScheduledEntryPoint() {
        var broken = (dgm.payment.payments.repository.UnmatchedNotificationRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {dgm.payment.payments.repository.UnmatchedNotificationRepository.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("база недоступна");
                });
        UnmatchedNotificationJob failing = new UnmatchedNotificationJob(broken, fake.payments, reconciliation, outcomes, ReconcileSettings.defaults(), clock,
                meters);

        failing.scheduled();
        job.scheduled();
    }
}
