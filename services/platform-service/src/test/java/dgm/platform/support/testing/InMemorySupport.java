package dgm.platform.support.testing;

import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.ResolvedTicket;
import dgm.platform.support.repository.SupportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Обращения и модель чтения в памяти с теми же правилами, что у таблиц: строка модели чтения одна на заказ, открытое обращение одно на
 * заказ (INV-21), «решено» не открывается заново, более раннее событие выдачи статус не меняет.
 */
public final class InMemorySupport implements SupportRepository {

    /** Строка {@code support.ticket}. */
    public static final class Ticket {
        public final UUID id;
        public final UUID orderId;
        public final UUID buyerId;
        public final String source;
        public final String reason;
        public String status = "created";
        public String resolvedBy;
        public Instant createdAt;
        public Instant resolvedAt;

        Ticket(UUID id, UUID orderId, UUID buyerId, String source, String reason, Instant createdAt) {
            this.id = id;
            this.orderId = orderId;
            this.buyerId = buyerId;
            this.source = source;
            this.reason = reason;
            this.createdAt = createdAt;
        }

        boolean open() {
            return "created".equals(status) || "in_progress".equals(status);
        }
    }

    private final Map<UUID, OrderView> views = new LinkedHashMap<>();
    private final List<Ticket> tickets = new ArrayList<>();

    @Override
    public void insertPaid(UUID orderId, long orderNumber, UUID buyerId, Instant at) {
        if (!views.containsKey(orderId)) {
            requireFreeNumber(orderId, orderNumber);
        }
        views.putIfAbsent(orderId, new OrderView(orderId, orderNumber, buyerId, "paid", Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Override
    public void upsertIssued(UUID orderId, long orderNumber, UUID buyerId, Instant issuedAt, Instant at) {
        OrderView old = views.get(orderId);
        if (old == null) {
            requireFreeNumber(orderId, orderNumber);
        }
        Optional<Instant> kept = old != null && old.issuedAt().isPresent() ? old.issuedAt() : Optional.of(issuedAt);
        views.put(orderId, new OrderView(orderId, orderNumber, buyerId, "issued", kept,
                old == null ? Optional.empty() : old.deliveryStatus(), old == null ? Optional.empty() : old.deliveryUpdatedAt()));
    }

    /** Номер заказа уникален, как {@code uq_order_view_order_number} в базе. */
    private void requireFreeNumber(UUID orderId, long orderNumber) {
        if (views.values().stream().anyMatch(v -> v.orderNumber() == orderNumber && !v.orderId().equals(orderId))) {
            throw new org.springframework.dao.DuplicateKeyException("uq_order_view_order_number");
        }
    }

    @Override
    public Optional<OrderView> findOrderView(UUID orderId) {
        return Optional.ofNullable(views.get(orderId));
    }

    @Override
    public boolean updateDelivery(UUID orderId, String deliveryStatus, Instant eventTime, Instant at) {
        OrderView old = views.get(orderId);
        if (old == null || old.deliveryUpdatedAt().filter(updated -> updated.isAfter(eventTime)).isPresent()) {
            return false;
        }
        views.put(orderId, new OrderView(orderId, old.orderNumber(), old.buyerId(), old.orderStatus(), old.issuedAt(),
                Optional.of(deliveryStatus), Optional.of(eventTime)));
        return true;
    }

    @Override
    public boolean insertSystemTicket(UUID ticketId, UUID orderId, UUID buyerId, String reason, Instant at) {
        if (tickets.stream().anyMatch(t -> t.orderId.equals(orderId) && t.open())) {
            return false;
        }
        tickets.add(new Ticket(ticketId, orderId, buyerId, "system", reason, at));
        return true;
    }

    @Override
    public List<ResolvedTicket> resolveOpenTickets(UUID orderId, Instant at) {
        List<ResolvedTicket> resolved = new ArrayList<>();
        for (Ticket ticket : tickets) {
            if (ticket.orderId.equals(orderId) && ticket.open()) {
                ticket.status = "resolved";
                ticket.resolvedBy = "system";
                ticket.resolvedAt = at;
                resolved.add(new ResolvedTicket(ticket.id, ticket.buyerId));
            }
        }
        return resolved;
    }

    public List<Ticket> tickets() {
        return List.copyOf(tickets);
    }

    public List<Ticket> ticketsOf(UUID orderId) {
        return tickets.stream().filter(t -> t.orderId.equals(orderId)).toList();
    }

    public int viewCount() {
        return views.size();
    }
}
