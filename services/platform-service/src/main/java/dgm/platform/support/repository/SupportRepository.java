package dgm.platform.support.repository;

import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.ResolvedTicket;
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
}
