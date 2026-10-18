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
}
