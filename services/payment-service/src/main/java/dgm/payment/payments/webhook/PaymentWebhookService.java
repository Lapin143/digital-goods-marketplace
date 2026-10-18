package dgm.payment.payments.webhook;

import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.payment.payments.domain.Payment;
import dgm.payment.payments.gateway.GatewayPaymentState;
import dgm.payment.payments.gateway.GatewayRejectedException;
import dgm.payment.payments.gateway.GatewayStatus;
import dgm.payment.payments.gateway.GatewayUnavailableException;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository.Unmatched;
import dgm.payment.payments.service.PaymentOutcomes;
import dgm.payment.payments.service.PaymentOutcomes.Application;
import dgm.payment.payments.service.PaymentOutcomes.Target;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обработка уведомления платёжного шлюза после проверки подписи и свежести метки времени (ADR-015, шаги 3 – 7; шаги 1 и 2 делает фильтр
 * каркаса {@code webhook-signature}). Порядок шагов:
 * <ol>
 *   <li>3: платёж находится по внешнему идентификатору, затем по заказу; внешний идентификатор, который ещё не записан, берётся из
 *       уведомления. Платежа нет: запись в {@code unmatched_notification}, ответ 200, дальше работает планировщик;</li>
 *   <li>4: сумма и валюта равны платёжным, иначе статус не меняется, исход {@code amount_mismatch}, метрика, ответ 200;</li>
 *   <li>5 (раньше 4а, чтобы повтор не нагружал шлюз): пара «платёж, тип» уже обработана: ответ 200, ничего не меняется;</li>
 *   <li>4а (Р-134): для «оплачен» запрашивается статус у шлюза одной попыткой; статус, сумма и валюта должны совпасть, иначе аномалия.
 *       Шлюз не ответил: 503, шлюз повторит уведомление, а сверка подберёт платёж сама;</li>
 *   <li>6: {@link PaymentOutcomes}, одна транзакция: запись события, смена статуса SM-02, запись в Outbox.</li>
 * </ol>
 * Истечение сессии статус платежа не меняет (платёж остаётся «создан» на случай поздней оплаты, SM-01/T6). Возврат
 * ({@code refund.completed}) обрабатывает модуль возвратов. Незнакомый тип принимается и игнорируется.
 */
public final class PaymentWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentWebhookService.class);

    /** Через сколько секунд шлюзу имеет смысл повторить уведомление, если мы не смогли спросить его о статусе. */
    static final int RETRY_AFTER_SECONDS = 5;

    /** Исход обработки для ответа и метрики. */
    public enum Result {
        APPLIED("applied"),
        DUPLICATE("duplicate"),
        UNMATCHED("unmatched"),
        AMOUNT_MISMATCH("amount-mismatch"),
        ANOMALY("anomaly"),
        IGNORED("ignored");

        private final String label;

        Result(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final UnmatchedNotificationRepository unmatched;
    private final PaymentGateway gateway;
    private final PaymentOutcomes outcomes;
    private final Clock clock;
    private final MeterRegistry meters;

    public PaymentWebhookService(PaymentRepository payments, PaymentEventRepository events, UnmatchedNotificationRepository unmatched,
                                 PaymentGateway gateway, PaymentOutcomes outcomes, Clock clock, MeterRegistry meters) {
        this.payments = payments;
        this.events = events;
        this.unmatched = unmatched;
        this.gateway = gateway;
        this.outcomes = outcomes;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @throws ProblemException 503 {@code dependency-unavailable}, если для подтверждения не удалось спросить шлюз о статусе
     */
    public Result receive(GatewayNotification n) {
        Result result = process(n);
        meters.counter("payment_webhooks_total", "result", result.label()).increment();
        return result;
    }

    private Result process(GatewayNotification n) {
        GatewayNotification.Kind kind = n.kind();
        if (kind != GatewayNotification.Kind.PAID && kind != GatewayNotification.Kind.DECLINED) {
            return Result.IGNORED;
        }
        Payment payment = find(n);
        if (payment == null) {
            unmatched.insertIfAbsent(new Unmatched(Uuids.v7(clock), n.paymentId(), n.type(), n.eventId(), n.orderId(), n.occurredAt(), clock.instant()));
            return Result.UNMATCHED;
        }
        if (!n.paymentId().equals(payment.gatewayPaymentId()) || !n.orderId().equals(payment.orderId())) {
            outcomes.recordUnapplied(payment, n.paymentId(), n.type(), n.eventId(), n.occurredAt(), PaymentEventRepository.ANOMALY, "gateway-id-mismatch");
            return Result.ANOMALY;
        }
        if (n.amount() != payment.amount() || !n.currency().equals(payment.currency())) {
            outcomes.recordUnapplied(payment, n.paymentId(), n.type(), n.eventId(), n.occurredAt(), PaymentEventRepository.AMOUNT_MISMATCH, "amount-mismatch");
            return Result.AMOUNT_MISMATCH;
        }
        if (events.exists(n.paymentId(), n.type())) {
            return Result.DUPLICATE;
        }
        Target target = kind == GatewayNotification.Kind.PAID ? Target.CONFIRM : Target.REJECT;
        if (target == Target.CONFIRM) {
            String disagreement = disagreement(n, payment);
            if (disagreement != null) {
                outcomes.recordUnapplied(payment, n.paymentId(), n.type(), n.eventId(), n.occurredAt(), PaymentEventRepository.ANOMALY, disagreement);
                return Result.ANOMALY;
            }
        }
        PaymentOutcomes.Result applied = outcomes.apply(new Application(n.paymentId(), n.type(), n.eventId(), target, payment.orderId(), n.occurredAt(),
                n.failureReason()));
        return switch (applied) {
            case APPLIED -> Result.APPLIED;
            case DUPLICATE -> Result.DUPLICATE;
            case ANOMALY -> Result.ANOMALY;
        };
    }

    /** Шаг 3: платёж по внешнему идентификатору, затем по заказу; пустой внешний идентификатор берётся из уведомления. */
    private Payment find(GatewayNotification n) {
        Payment byGateway = payments.findByGatewayPaymentId(n.paymentId()).orElse(null);
        if (byGateway != null) {
            return byGateway;
        }
        Payment byOrder = payments.findByOrder(n.orderId()).orElse(null);
        if (byOrder == null) {
            return null;
        }
        if (byOrder.gatewayPaymentId() == null) {
            // Уведомление обогнало вторую транзакцию открытия сессии: внешний идентификатор записывается один раз, проигравший гонку перечитывает
            return payments.adoptGatewayPaymentId(byOrder.id(), n.paymentId()).or(() -> payments.findByOrder(n.orderId())).orElse(null);
        }
        return byOrder;
    }

    /** Шаг 4а: что шлюз скажет сам. {@code null}, если шлюз подтверждает уведомление. */
    private String disagreement(GatewayNotification n, Payment payment) {
        GatewayPaymentState state;
        try {
            state = gateway.checkStatus(n.paymentId());
        } catch (GatewayUnavailableException e) {
            meters.counter("payment_webhooks_total", "result", "gateway-unavailable").increment();
            LOG.warn("Шлюз не ответил на запрос статуса платежа {}: уведомление {} будет повторено", payment.id(), n.eventId());
            throw new ProblemException(ProblemType.DEPENDENCY_UNAVAILABLE, "Не удалось проверить статус платежа у шлюза, повторите уведомление.",
                    Map.of(), Map.of("Retry-After", Integer.toString(RETRY_AFTER_SECONDS)));
        } catch (GatewayRejectedException e) {
            return "gateway-unknown-payment";
        }
        if (state.status() != GatewayStatus.PAID) {
            return "gateway-status-mismatch";
        }
        return state.sameMoney(payment.amount(), payment.currency()) ? null : "gateway-amount-mismatch";
    }
}
