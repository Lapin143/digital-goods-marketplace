package dgm.platform.support.repository;

import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.QueueDepth;
import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.ResolvedTicket;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.domain.TicketFilter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Обращения и модель чтения заказа (схема {@code support}). Методы вызываются внутри транзакции потребителя модуля {@code support}. */
public interface SupportRepository {

    /** Создаёт модель чтения по {@code order.paid}; если строка уже есть (более позднее событие её опередило), ничего не меняет. */
    void insertPaid(UUID orderId, long orderNumber, UUID buyerId, Instant at);

    /**
     * Создаёт или дополняет модель чтения по {@code order.issued}: статус «выдан», время первичной выдачи записывается один раз
     * (INV-17), повторное событие её не сдвигает.
     */
    void upsertIssued(UUID orderId, long orderNumber, UUID buyerId, Instant issuedAt, Instant at);

    Optional<OrderView> findOrderView(UUID orderId);

    /**
     * Записывает статус выдачи, если событие не старше уже применённого.
     *
     * @param eventTime время события (конверт), по нему отсекаются перестановки
     * @return {@code false}: строки нет или применено более новое событие
     */
    boolean updateDelivery(UUID orderId, String deliveryStatus, Instant eventTime, Instant at);

    /**
     * Создаёт обращение системы со статусом «создано» (SM-07/T1).
     *
     * @return {@code false}: по заказу уже есть открытое обращение (INV-21), второе не создано
     */
    boolean insertSystemTicket(UUID ticketId, UUID orderId, UUID buyerId, String reason, Instant at);

    /** Закрывает все открытые обращения заказа фактом доставки (SM-07/T4, закрывает система) и возвращает закрытые. */
    List<ResolvedTicket> resolveOpenTickets(UUID orderId, Instant at);

    /**
     * Создаёт обращение покупателя «не получил ключ» со статусом «создано» (SM-07/T1).
     *
     * @return {@code false}: по заказу уже есть открытое обращение (INV-21), второе не создано
     */
    boolean insertBuyerTicket(UUID ticketId, UUID orderId, UUID buyerId, Instant at);

    Optional<SupportTicket> findTicket(UUID ticketId);

    /** Обращения покупателя по условиям выборки: не больше {@code limit + 1} строк, лишняя говорит, что есть следующая страница. */
    List<SupportTicket> findTickets(TicketFilter filter);

    /**
     * Очередь оператора: обращения вместе с моделью чтения заказа, старые выше. Не больше {@code limit + 1} строк, лишняя говорит, что есть
     * следующая страница.
     */
    List<StaffTicket> findQueue(QueueFilter filter);

    /** Карточка обращения для оператора. */
    Optional<StaffTicket> findStaffTicket(UUID ticketId);

    /**
     * Берёт обращение в работу одним условным {@code UPDATE ... WHERE status = 'created'} (SM-07/T2, правило 15): из двух операторов,
     * пришедших одновременно, обновление получает один.
     *
     * @return {@code false}: обращения нет или оно уже не в статусе «создано»
     */
    boolean takeTicket(UUID ticketId, UUID operatorId, Instant at);

    /**
     * Возвращает обращение в очередь (SM-07/T3): оператор и время взятия сбрасываются, время создания не меняется.
     *
     * @return {@code false}: обращения нет, оно не в работе или в работе у другого оператора
     */
    boolean releaseTicket(UUID ticketId, UUID operatorId);

    /**
     * Запоминает решение оператора о повторной отправке: {@code action = 'resend'} у обращения, которое в работе у этого оператора. Условие
     * проверяется тем же оператором ещё раз: пока шла проверка, обращение могли вернуть в очередь.
     *
     * @return {@code false}: обращения нет, оно не в работе или в работе у другого оператора
     */
    boolean markResend(UUID ticketId, UUID operatorId);

    /** Число открытых обращений по статусам и время самого старого из «созданных» (метрики очереди). */
    QueueDepth queueDepth();
}
