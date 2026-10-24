package dgm.platform.support.service;

import dgm.kit.events.HandleResult;
import dgm.kit.events.RetryableEventException;
import dgm.kit.id.Uuids;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.ResolvedTicket;
import dgm.platform.support.domain.TicketReason;
import dgm.platform.support.repository.SupportRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ядро поддержки среза 2 (компонент {@code support-event-handler}, Р-137, SM-07): модель чтения заказа по событиям заказа и выдачи,
 * обращение системы по {@code delivery.failed} и {@code delivery.overdue}, закрытие обращения по {@code delivery.delivered}. Методы
 * вызываются обработчиками внутри транзакции потребителя модуля {@code support}: изменение строк и запись события в Outbox фиксируются
 * вместе или не фиксируются вовсе. Операций оператора здесь нет: очередь, взятие в работу и повторная отправка относятся к срезу 3.
 *
 * <p>Событие выдачи раньше {@code order.paid} (F3-21): модели чтения ещё нет, а у обращения покупатель обязателен. Событие, которое
 * создаёт обращение ({@code delivery.failed}, {@code delivery.overdue}), не теряется и не создаёт обращение без покупателя: служба бросает
 * {@link RetryableEventException}, потребитель повторяет его с паузами 1, 5 и 25 секунд, а если модели так и нет, событие уходит в
 * {@code delivery.events.dlq} и после {@code order.paid} возвращается оттуда (шаг 16). События, которые лишь обновляют статус
 * ({@code delivery.accepted}, {@code delivery.delivered}), пропускаются с причиной {@code no-order-view}: обращения без модели чтения быть не
 * может, закрывать нечего, а повтор бесполезен, потому что поток потребителя один и {@code order.paid} он не прочитает, пока ждёт повтора.
 */
public final class SupportService {

    private static final Logger LOG = LoggerFactory.getLogger(SupportService.class);
    private static final String NO_ORDER_VIEW = "no-order-view";

    private final SupportRepository repository;
    private final SupportOutbox outbox;
    private final Clock clock;

    public SupportService(SupportRepository repository, SupportOutbox outbox, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** {@code order.paid}: покупатель и номер заказа попадают в модель чтения. */
    public HandleResult onOrderPaid(UUID orderId, long orderNumber, UUID buyerId) {
        repository.insertPaid(orderId, orderNumber, buyerId, clock.instant());
        return HandleResult.done();
    }

    /** {@code order.issued}: заказ «выдан», время первичной выдачи записывается один раз. Строку создаёт и это событие: в нём есть всё нужное. */
    public HandleResult onOrderIssued(UUID orderId, long orderNumber, UUID buyerId, Instant issuedAt) {
        repository.upsertIssued(orderId, orderNumber, buyerId, issuedAt, clock.instant());
        return HandleResult.done();
    }

    /** {@code delivery.accepted}: провайдер принял письмо, статус выдачи «отправлено». */
    public HandleResult onDeliveryAccepted(UUID orderId, Instant eventTime) {
        OrderView view = repository.findOrderView(orderId).orElse(null);
        if (view == null) {
            return HandleResult.ignored(NO_ORDER_VIEW);
        }
        if (view.deliveryNewerThan(eventTime)) {
            return HandleResult.ignored("stale-event");
        }
        repository.updateDelivery(orderId, OrderView.SENT, eventTime, clock.instant());
        return HandleResult.done();
    }

    /**
     * {@code delivery.delivered}: статус «доставлено» и закрытие открытых обращений заказа (SM-07/T4, закрывает система). Событие, которое
     * старше уже применённого, ничего не закрывает: обращение, созданное более поздним событием, этой доставкой не решено.
     */
    public HandleResult onDeliveryDelivered(UUID orderId, Instant eventTime) {
        OrderView view = repository.findOrderView(orderId).orElse(null);
        if (view == null) {
            return HandleResult.ignored(NO_ORDER_VIEW);
        }
        if (view.deliveryNewerThan(eventTime)) {
            return HandleResult.ignored("stale-event");
        }
        Instant now = clock.instant();
        repository.updateDelivery(orderId, OrderView.DELIVERED, eventTime, now);
        List<ResolvedTicket> resolved = repository.resolveOpenTickets(orderId, now);
        for (ResolvedTicket ticket : resolved) {
            outbox.publish(SupportEvents.TOPIC, SupportEvents.AGGREGATE, ticket.id().toString(), SupportEvents.RESOLVED, 1,
                    SupportEvents.resolved(ticket.id(), orderId, ticket.buyerId(), "system"));
            LOG.info("Обращение {} по заказу {} решено доставкой", ticket.id(), orderId);
        }
        return HandleResult.done();
    }

    /** {@code delivery.failed}: статус «ошибка» и обращение системы с причиной «исчерпаны попытки» или «не доставлено» (SM-07/T1). */
    public HandleResult onDeliveryFailed(UUID orderId, String failureReason, Instant eventTime) {
        OrderView view = requireView(orderId, "delivery.failed");
        if (view.deliveredAfter(eventTime)) {
            return HandleResult.ignored("stale-event");
        }
        repository.updateDelivery(orderId, OrderView.FAILED, eventTime, clock.instant());
        return openSystemTicket(view, TicketReason.forFailure(failureReason));
    }

    /** {@code delivery.overdue}: 30 минут после оплаты без доставки, обращение системы с причиной «срок выдачи» (SM-07/T1, NFT-2.4). */
    public HandleResult onDeliveryOverdue(UUID orderId, Instant eventTime) {
        OrderView view = requireView(orderId, "delivery.overdue");
        if (view.deliveredAfter(eventTime)) {
            return HandleResult.ignored("stale-event");
        }
        return openSystemTicket(view, TicketReason.DELIVERY_OVERDUE);
    }

    private HandleResult openSystemTicket(OrderView view, String reason) {
        UUID ticketId = Uuids.v7(clock);
        if (!repository.insertSystemTicket(ticketId, view.orderId(), view.buyerId(), reason, clock.instant())) {
            LOG.info("По заказу {} уже есть открытое обращение, второе не создано", view.orderId());
            return HandleResult.ignored("ticket-already-open");
        }
        outbox.publish(SupportEvents.TOPIC, SupportEvents.AGGREGATE, ticketId.toString(), SupportEvents.CREATED, 1,
                SupportEvents.created(ticketId, view.orderId(), view.buyerId(), "system", reason));
        LOG.info("Обращение системы {} по заказу {} создано, причина {}", ticketId, view.orderId(), reason);
        return HandleResult.done();
    }

    private OrderView requireView(UUID orderId, String eventType) {
        return repository.findOrderView(orderId).orElseThrow(() ->
                new RetryableEventException("Модели чтения заказа " + orderId + " ещё нет, событие " + eventType + " повторится (F3-21)"));
    }
}
