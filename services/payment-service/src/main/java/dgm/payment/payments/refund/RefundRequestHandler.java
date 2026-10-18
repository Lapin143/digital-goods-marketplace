package dgm.payment.payments.refund;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.repository.JdbcPaymentRepository;
import dgm.payment.payments.service.RefundOutcomes;
import dgm.payment.payments.service.RefundOutcomes.Request;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Обработчик {@code order.refund-requested} (компонент {@code refund-service} в контракте событий, ADR-013, ADR-015, US-5.7): запоминает
 * причину и назначает первую попытку возврата; сам шлюз вызывает исполнитель возвратов, не потребитель (правило 9). Повтор команды
 * второго возврата не создаёт: платёж уже возвращён, возврат выполняется или ждёт администратора, событие игнорируется с причиной.
 * Команда не к нашим данным (нет платежа, платёж не оплачен, сумма не равна платёжной) это неисправимая ошибка: сообщение уходит в
 * {@code order.events.dlq} без повторов. Работает внутри транзакции потребителя под ролью модуля {@code payments}.
 */
@Component
public final class RefundRequestHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "refund-service";

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final RefundOutcomes outcomes;

    public RefundRequestHandler(RefundOutcomes outcomes) {
        this.outcomes = outcomes;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String module() {
        return JdbcPaymentRepository.MODULE;
    }

    @Override
    public String topic() {
        return "order.events";
    }

    @Override
    public Set<String> types() {
        return Set.of("order.refund-requested");
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        Request request = request(event);
        return switch (outcomes.request(request)) {
            case STARTED -> HandleResult.done();
            case ALREADY_REFUNDED -> HandleResult.ignored("already-refunded");
            case IN_PROGRESS -> HandleResult.ignored("refund-in-progress");
            case IN_QUEUE -> HandleResult.ignored("in-admin-queue");
            case NO_PAYMENT -> throw new FatalEventException("Возврат по событию " + event.id() + ": платежа по заказу " + request.orderId() + " нет");
            case NOT_PAID -> throw new FatalEventException("Возврат по событию " + event.id() + ": платёж заказа " + request.orderId() + " не оплачен");
            case AMOUNT_MISMATCH -> throw new FatalEventException("Возврат по событию " + event.id() + ": сумма не равна сумме платежа заказа "
                    + request.orderId());
        };
    }

    /** Разбирает данные события по схеме {@code OrderRefundRequestedData}: любое нарушение это неисправимая ошибка. */
    static Request request(EventEnvelope event) {
        Map<String, Object> data = event.data();
        String orderId = text(event, data, "orderId");
        if (!ID.matcher(orderId).matches()) {
            throw new FatalEventException("orderId события " + event.id() + " не UUID в нижнем регистре");
        }
        if (!(data.get("amount") instanceof Map<?, ?> money) || !(money.get("amount") instanceof Number amount) || !(money.get("currency") instanceof String currency)
                || !CURRENCY.matcher(currency).matches() || amount.longValue() <= 0 || amount.doubleValue() != amount.longValue()) {
            throw new FatalEventException("amount события " + event.id() + " не сумма в копейках и валюта из трёх заглавных букв");
        }
        String reason = text(event, data, "refundReason");
        if (!RefundPayment.REASONS.contains(reason)) {
            throw new FatalEventException("refundReason события " + event.id() + " вне перечисления контракта");
        }
        return new Request(UUID.fromString(orderId), amount.longValue(), currency, reason);
    }

    private static String text(EventEnvelope event, Map<String, Object> data, String field) {
        if (data.get(field) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new FatalEventException("В событии " + event.id() + " нет поля " + field);
    }
}
