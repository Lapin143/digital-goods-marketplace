package dgm.payment.payments.repository;

import dgm.payment.payments.domain.Payment;
import java.time.Instant;
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
}
