package dgm.payment.payments.session;

import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayPayment;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.service.PaymentTransaction;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Открытие платёжной сессии ({@code openPaymentSession}, SEQ-01 шаги 12 и 13, ADR-015). Три шага, и вызов шлюза не внутри транзакции
 * (правило 9):
 * <ol>
 *   <li>транзакция 1: платёж «создан» без внешнего идентификатора; уникальность по заказу (INV-11) делает вставку идемпотентной, повторный
 *       вызов по тому же заказу находит эту строку;</li>
 *   <li>вызов шлюза с {@code Idempotency-Key}, равным идентификатору платежа: потерянный ответ не приводит ко второму платежу у шлюза;</li>
 *   <li>транзакция 2: внешний идентификатор, адрес страницы оплаты и срок сессии.</li>
 * </ol>
 * Шлюз не ответил за общий срок: 503 {@code payment-session-unavailable}, строка остаётся без внешнего идентификатора (платежа у шлюза нет
 * или он неизвестен, сверка такую строку не трогает), заказ отменяется по SM-01/T12; повторный вызов продолжит с шага 2.
 */
public final class PaymentSessionService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSessionService.class);

    /** Через сколько секунд вызывающему имеет смысл повторить, если шлюз не ответил. */
    static final int RETRY_AFTER_SECONDS = 5;

    private final PaymentRepository payments;
    private final PaymentTransaction transaction;
    private final PaymentGateway gateway;
    private final SessionSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentSessionService(PaymentRepository payments, PaymentTransaction transaction, PaymentGateway gateway,
                                 SessionSettings settings, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.transaction = transaction;
        this.gateway = gateway;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @return платёж «создан» с адресом страницы оплаты и сроком сессии
     * @throws ProblemException 409 {@code state-conflict}, если платёж по заказу уже не «создан» или заведён на другую сумму;
     *                          422, если срок сессии уже прошёл или длиннее срока резерва; 503 {@code payment-session-unavailable}
     */
    public Payment open(UUID orderId, long amount, String currency, Instant sessionExpiresAt) {
        Instant now = clock.instant();
        checkExpiry(sessionExpiresAt, now);

        // Транзакция 1: платёж «создан» (или уже существующий платёж заказа)
        Payment current = transaction.execute(() -> {
            payments.insertIfAbsent(new Payment(Uuids.v7(clock), orderId, null, amount, currency, null, null, Payment.CREATED), now);
            return payments.findByOrder(orderId).orElseThrow(() -> new IllegalStateException("Платёж заказа не найден после вставки"));
        });
        if (current.amount() != amount || !current.currency().equals(currency)) {
            count("conflict");
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Платёж по этому заказу уже создан на другую сумму.");
        }
        if (!current.isCreated()) {
            count("conflict");
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Платёж по этому заказу уже в статусе «" + current.status() + "».",
                    Map.of("currentStatus", current.status()), Map.of());
        }
        if (current.hasSession()) {
            count("replayed");
            return current;
        }

        // Вызов шлюза вне транзакции
        GatewayPayment created;
        try {
            created = gateway.create(current.id(), orderId, amount, currency, settings.returnUrl(orderId), sessionExpiresAt);
        } catch (GatewayUnavailableException e) {
            count("unavailable");
            LOG.warn("Шлюз не открыл платёжную сессию: платёж {}, заказ {}", current.id(), orderId);
            throw unavailable();
        } catch (GatewayRejectedException e) {
            count("rejected");
            LOG.error("Шлюз отклонил создание платежа: платёж {}, заказ {}, код {}", current.id(), orderId, e.status());
            throw unavailable();
        }

        // Транзакция 2: внешний идентификатор, адрес и срок сессии
        Payment saved = transaction.execute(() -> payments.attachSession(current.id(), created.id(), created.payUrl(), sessionExpiresAt)
                .or(() -> payments.findByOrder(orderId)).orElseThrow(() -> new IllegalStateException("Платёж заказа пропал")));
        count("opened");
        return saved.hasSession() ? saved
                : new Payment(saved.id(), saved.orderId(), saved.gatewayPaymentId() == null ? created.id() : saved.gatewayPaymentId(), saved.amount(),
                        saved.currency(), created.payUrl(), sessionExpiresAt, saved.status());
    }

    private void checkExpiry(Instant sessionExpiresAt, Instant now) {
        String problem = null;
        if (!sessionExpiresAt.isAfter(now)) {
            problem = "Срок платёжной сессии уже прошёл.";
        } else if (sessionExpiresAt.isAfter(now.plus(settings.reservationTtl()))) {
            problem = "Платёжная сессия не может быть длиннее резерва ключей (INV-07).";
        }
        if (problem != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("pointer", "/sessionExpiresAt");
            error.put("code", "out_of_range");
            error.put("detail", problem);
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.of(error)), Map.of());
        }
    }

    private static ProblemException unavailable() {
        return new ProblemException(ProblemType.PAYMENT_SESSION_UNAVAILABLE, "Не удалось открыть оплату, заказ отменён.", Map.of(),
                Map.of("Retry-After", Integer.toString(RETRY_AFTER_SECONDS)));
    }

    private void count(String result) {
        meters.counter("payment_sessions_total", "result", result).increment();
    }
}
