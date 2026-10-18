package dgm.payment.payments.gateway;

/**
 * Возврат у шлюза в модели платформы.
 *
 * @param id        внешний идентификатор возврата, непрозрачная строка
 * @param completed {@code true}: деньги уже возвращены ({@code instant}); {@code false}: шлюз принял возврат и выполняет его, итог придёт
 *                  уведомлением {@code refund.completed} или его найдёт сверка
 */
public record GatewayRefund(String id, boolean completed) {
}
