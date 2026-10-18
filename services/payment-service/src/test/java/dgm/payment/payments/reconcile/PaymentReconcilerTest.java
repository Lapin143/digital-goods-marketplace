package dgm.payment.payments.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.time.ManualClock;
import dgm.payment.payments.FakePayments;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.service.PaymentOutcomes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Проходы сверки: молодые платежи, платежи среднего возраста и счёт платежей, которые сверка больше не берёт. */
@Tag("TC-508")
class PaymentReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final long AMOUNT = 100000;

    private FakePayments fake;
    private SimpleMeterRegistry meters;
    private ManualClock clock;
    private PaymentReconciler reconciler;

    @BeforeEach
    void setUp() {
        fake = new FakePayments();
        meters = new SimpleMeterRegistry();
        clock = new ManualClock(NOW);
        PaymentOutcomes outcomes = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, clock, meters);
        PaymentReconciliation reconciliation = new PaymentReconciliation(fake.gateway, outcomes, clock, meters);
        reconciler = new PaymentReconciler(fake.payments, reconciliation, ReconcileSettings.defaults(), clock, meters);
    }

    /** Платёж «создан», созданный {@code age} назад, шлюз знает его как оплаченный. */
    private UUID open(int number, Duration age) {
        UUID order = UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 300 + number));
        String gatewayId = "pay_" + number;
        fake.payments.putCreated(new Payment(UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", 400 + number)), order, gatewayId, AMOUNT, "RUB",
                "https://x/pay", NOW.plusSeconds(60), Payment.CREATED), NOW.minus(age));
        fake.gateway.know(gatewayId, GatewayStatus.PAID, AMOUNT, "RUB");
        return order;
    }

    private String status(UUID order) {
        return fake.payments.byOrder.get(order).status();
    }

    @Test
    void theYoungPassTakesOnlyPaymentsYoungerThanTwoHours() {
        UUID young = open(1, Duration.ofMinutes(10));
        UUID middle = open(2, Duration.ofHours(3));
        UUID stale = open(3, Duration.ofHours(30));

        assertEquals(1, reconciler.runYoungPass());

        assertEquals(Payment.CONFIRMED, status(young));
        assertEquals(Payment.CREATED, status(middle));
        assertEquals(Payment.CREATED, status(stale));
    }

    @Test
    void theOldPassTakesPaymentsBetweenTwoAndTwentyFourHoursAndCountsTheRest() {
        UUID young = open(1, Duration.ofMinutes(10));
        UUID middle = open(2, Duration.ofHours(3));
        UUID stale = open(3, Duration.ofHours(30));

        assertEquals(1, reconciler.runOldPass());

        assertEquals(Payment.CREATED, status(young));
        assertEquals(Payment.CONFIRMED, status(middle));
        assertEquals(Payment.CREATED, status(stale), "старше суток платёж идёт в отчёт, а не в сверку");
        assertEquals(1.0, meters.get("payments_stale_open").gauge().value());
    }

    @Test
    void theStaleCountDropsWhenNothingIsLeft() {
        open(3, Duration.ofHours(30));
        reconciler.runOldPass();
        assertEquals(1.0, meters.get("payments_stale_open").gauge().value());

        fake.payments.byOrder.clear();
        reconciler.runOldPass();
        assertEquals(0.0, meters.get("payments_stale_open").gauge().value());
    }

    @Test
    void aPassHandlesNoMoreThanTheBatchAndTheRestWaitsForTheNextPass() {
        PaymentOutcomes outcomes = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, clock, meters);
        PaymentReconciliation reconciliation = new PaymentReconciliation(fake.gateway, outcomes, clock, meters);
        PaymentReconciler small = new PaymentReconciler(fake.payments, reconciliation,
                new ReconcileSettings(Duration.ofHours(2), Duration.ofHours(24), Duration.ofHours(1), 2), clock, meters);
        for (int i = 1; i <= 5; i++) {
            open(i, Duration.ofMinutes(i));
        }

        assertEquals(2, small.runYoungPass());
        assertEquals(2, small.runYoungPass());
        assertEquals(1, small.runYoungPass());
        assertEquals(0, small.runYoungPass());
    }

    @Test
    void aPaymentTheGatewayDoesNotAnswerForDoesNotBlockTheOthers() {
        UUID first = open(1, Duration.ofMinutes(5));
        UUID second = open(2, Duration.ofMinutes(6));
        fake.gateway.statusFailures = 1;

        assertEquals(1, reconciler.runYoungPass());

        long confirmed = java.util.stream.Stream.of(first, second).filter(o -> Payment.CONFIRMED.equals(status(o))).count();
        assertEquals(1, confirmed);
        assertEquals(1, reconciler.runYoungPass(), "следующий проход подбирает оставшийся");
    }

    @Test
    void theScheduledEntryPointsRunTheSamePasses() {
        UUID young = open(1, Duration.ofMinutes(5));
        UUID middle = open(2, Duration.ofHours(3));

        reconciler.youngScheduled();
        assertEquals(Payment.CONFIRMED, status(young));
        assertEquals(Payment.CREATED, status(middle));

        reconciler.oldScheduled();
        assertEquals(Payment.CONFIRMED, status(middle));
    }

    @Test
    void aFailureOfAPassIsSwallowedByTheScheduledEntryPoints() {
        PaymentRepository broken = (PaymentRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PaymentRepository.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("база недоступна");
                });
        PaymentOutcomes outcomes = new PaymentOutcomes(fake.payments, fake.events, fake.transaction, clock, meters);
        PaymentReconciler failing = new PaymentReconciler(broken, new PaymentReconciliation(fake.gateway, outcomes, clock, meters),
                ReconcileSettings.defaults(), clock, meters);

        failing.youngScheduled();
        failing.oldScheduled();
    }

    @Test
    void settingsAreValidated() {
        assertThrows(IllegalStateException.class, () -> new ReconcileSettings(Duration.ZERO, Duration.ofHours(24), Duration.ofHours(1), 10));
        assertThrows(IllegalStateException.class, () -> new ReconcileSettings(Duration.ofHours(2), Duration.ofHours(2), Duration.ofHours(1), 10));
        assertThrows(IllegalStateException.class, () -> new ReconcileSettings(Duration.ofHours(2), Duration.ofHours(24), Duration.ofSeconds(-1), 10));
        assertThrows(IllegalStateException.class, () -> new ReconcileSettings(Duration.ofHours(2), Duration.ofHours(24), Duration.ofHours(1), 0));
        assertEquals(Duration.ofHours(24), ReconcileSettings.defaults().maxAge());
    }
}
