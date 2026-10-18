package dgm.payment.payments.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * Уведомление платёжного шлюза после разбора (схема {@code PaymentGatewayNotification}).
 *
 * @param eventId       идентификатор уведомления у шлюза, непрозрачная строка
 * @param type          тип уведомления как прислал шлюз
 * @param occurredAt    время события по данным шлюза
 * @param paymentId     внешний идентификатор платежа
 * @param orderId       заказ из метаданных платежа
 * @param amount        сумма в копейках
 * @param currency      валюта
 * @param refundId      внешний идентификатор возврата или {@code null}
 * @param failureReason причина отказа или {@code null}
 */
public record GatewayNotification(String eventId, String type, Instant occurredAt, String paymentId, UUID orderId, long amount, String currency,
                                  String refundId, String failureReason) {

    /** Тип уведомления в терминах платформы. Перечисление шлюза открытое: неизвестный тип принимается и игнорируется. */
    public enum Kind {
        PAID("payment.paid"),
        DECLINED("payment.declined"),
        SESSION_EXPIRED("payment.session-expired"),
        REFUND_COMPLETED("refund.completed"),
        UNKNOWN("");

        private final String type;

        Kind(String type) {
            this.type = type;
        }

        public String type() {
            return type;
        }
    }

    public Kind kind() {
        for (Kind kind : Kind.values()) {
            if (kind != Kind.UNKNOWN && kind.type().equals(type)) {
                return kind;
            }
        }
        return Kind.UNKNOWN;
    }
}
