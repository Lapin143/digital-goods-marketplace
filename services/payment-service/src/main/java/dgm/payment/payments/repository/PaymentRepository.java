package dgm.payment.payments.repository;

import dgm.payment.payments.domain.Payment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Платежи в базе сервиса. Методы вызываются внутри транзакции модуля {@code payments}. */
public interface PaymentRepository {

    Optional<Payment> findByOrder(UUID orderId);

    /**
     * Вставляет платёж «создан» без внешнего идентификатора. Платёж по заказу уже есть (уникальность {@code uq_payment_order_id}, INV-11):
     * ничего не вставляется.
     *
     * @return {@code true}, если строка вставлена этим вызовом
     */
    boolean insertIfAbsent(Payment payment, Instant at);

    /**
     * Записывает внешний идентификатор, адрес и срок сессии, пока они не записаны. Платёж уже получил сессию или вышел из статуса «создан»:
     * пусто, вызывающий перечитывает строку.
     */
    Optional<Payment> attachSession(UUID id, String gatewayPaymentId, String sessionUrl, Instant sessionExpiresAt);

    Optional<Payment> findByGatewayPaymentId(String gatewayPaymentId);

    /** Читает платёж заказа и блокирует строку до конца транзакции ({@code FOR UPDATE}): смена статуса идёт под этой блокировкой. */
    Optional<Payment> lockByOrder(UUID orderId);

    /**
     * Записывает внешний идентификатор из уведомления, если он ещё не записан (ADR-015, шаг 3): уведомление может обогнать вторую
     * транзакцию открытия сессии. Идентификатор уже записан: пусто.
     */
    Optional<Payment> adoptGatewayPaymentId(UUID id, String gatewayPaymentId);

    /** {@code created → confirmed}. Платёж не в статусе «создан»: пусто. */
    Optional<Payment> confirm(UUID id, Instant at);

    /** {@code created → rejected}. Платёж не в статусе «создан»: пусто. */
    Optional<Payment> reject(UUID id, Instant at);

    /**
     * Платежи «создан» с внешним идентификатором, созданные в заданном промежутке, старые первыми (сверка со шлюзом).
     *
     * @param createdAfter  не раньше этого момента
     * @param createdBefore не позже этого момента
     */
    List<Payment> openBetween(Instant createdAfter, Instant createdBefore, int limit);
}
