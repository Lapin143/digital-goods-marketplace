package dgm.payment.payments.gateway;

import java.time.Instant;
import java.util.UUID;

/**
 * Слой защиты от изменений (ACL) между платформой и платёжным шлюзом (ADR-015): внутри платформы работает только своя модель платёжной
 * сессии, формат и статусы шлюза остаются в реализации. Смена провайдера это новая реализация интерфейса.
 *
 * <p>Методы блокируют вызывающего до ответа и не должны вызываться внутри транзакции базы. Ошибки: {@link GatewayUnavailableException}
 * (ответа нет, результат неизвестен) и {@link GatewayRejectedException} (шлюз принял решение не выполнять запрос).
 */
public interface PaymentGateway {

    /**
     * Создаёт платёж у шлюза. Ключ идемпотентности равен идентификатору нашего платежа: повтор после потерянного ответа возвращает тот же
     * платёж шлюза, а не создаёт второй (ADR-015, BPMN-01, решение 7).
     *
     * @param paymentId  идентификатор платежа платформы, он же ключ идемпотентности
     * @param orderId    заказ, уходит в метаданные платежа: по нему вебхук находит платёж
     * @param amount     сумма в копейках
     * @param currency   валюта
     * @param returnUrl  страница, на которую шлюз возвращает покупателя (она ничего не решает)
     * @param expiresAt  срок платёжной сессии
     */
    GatewayPayment create(UUID paymentId, UUID orderId, long amount, String currency, String returnUrl, Instant expiresAt);

    /**
     * Запрашивает статус платежа одной попыткой (шаг 4а вебхука, Р-134): вебхук отвечает шлюзу в пределах его тайм-аута, повтор
     * внутри вебхука не нужен, потому что шлюз повторит вебхук, а сверка подберёт платёж сама.
     *
     * @throws GatewayUnavailableException шлюз не ответил за время попытки
     * @throws GatewayRejectedException    шлюз не знает такого платежа или отклонил запрос
     */
    GatewayPaymentState checkStatus(String gatewayPaymentId);

    /**
     * Запрашивает статус платежа с повторами по расписанию клиента шлюза: для сверки, где времени больше, чем у вебхука.
     *
     * @throws GatewayUnavailableException шлюз не ответил за общий срок
     * @throws GatewayRejectedException    шлюз не знает такого платежа или отклонил запрос
     */
    GatewayPaymentState lookupStatus(String gatewayPaymentId);

    /**
     * Создаёт возврат на полную сумму платежа. Ключ идемпотентности это {@code refund-} и идентификатор нашего платежа (ADR-015): повтор
     * после потерянного ответа и повтор по расписанию возвращают тот же возврат, а не создают второй (INV-15). Вызывается с повторами
     * клиента шлюза: у исполнителя возвратов времени больше, чем у вебхука.
     *
     * @param paymentId        платёж платформы, от него зависит ключ
     * @param gatewayPaymentId внешний идентификатор платежа
     * @param amount           сумма платежа в копейках: шлюз принимает только полную
     * @param currency         валюта платежа
     * @throws GatewayUnavailableException шлюз не ответил за общий срок
     * @throws GatewayRejectedException    шлюз возврат не принимает (4xx)
     */
    GatewayRefund createRefund(UUID paymentId, String gatewayPaymentId, long amount, String currency);
}
