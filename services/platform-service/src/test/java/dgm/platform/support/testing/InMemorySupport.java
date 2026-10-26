package dgm.platform.support.testing;

import dgm.platform.support.domain.EmailChange;
import dgm.platform.support.domain.EmailChangeView;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.QueueDepth;
import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.ResolvedTicket;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.domain.TicketFilter;
import dgm.platform.support.repository.EmailChangeRepository;
import dgm.platform.support.repository.SupportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Обращения и модель чтения в памяти с теми же правилами, что у таблиц: строка модели чтения одна на заказ, открытое обращение одно на
 * заказ (INV-21), «решено» не открывается заново, более раннее событие выдачи статус не меняет.
 */
public final class InMemorySupport implements SupportRepository, EmailChangeRepository {

    /** Строка {@code support.ticket}. */
    public static final class Ticket {
        public final UUID id;
        public final UUID orderId;
        public final UUID buyerId;
        public final String source;
        public final String reason;
        public String status = "created";
        public String resolvedBy;
        public String action;
        public String codeCheckResult;
        public Instant createdAt;
        public Instant resolvedAt;
        public UUID operatorId;
        public Instant takenAt;

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
    /** Строки {@code support.email_change}: записи неизменяемые, поэтому снимок для отката это копия отображения. */
    private final Map<UUID, EmailChange> changes = new LinkedHashMap<>();

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
                abandon(ticket.id, at);
            }
        }
        return resolved;
    }

    @Override
    public boolean insertBuyerTicket(UUID ticketId, UUID orderId, UUID buyerId, Instant at) {
        if (tickets.stream().anyMatch(t -> t.orderId.equals(orderId) && t.open())) {
            return false;
        }
        tickets.add(new Ticket(ticketId, orderId, buyerId, "buyer", "key_not_received", at));
        return true;
    }

    @Override
    public Optional<SupportTicket> findTicket(UUID ticketId) {
        return tickets.stream().filter(t -> t.id.equals(ticketId)).findFirst().map(this::domain);
    }

    @Override
    public List<SupportTicket> findTickets(TicketFilter filter) {
        return tickets.stream()
                .filter(t -> t.buyerId.equals(filter.buyerId()))
                .filter(t -> filter.orderId().map(t.orderId::equals).orElse(true))
                .filter(t -> filter.statuses().isEmpty() || filter.statuses().contains(t.status))
                .filter(t -> filter.after().map(a -> t.createdAt.isBefore(a.createdAt())
                        || (t.createdAt.equals(a.createdAt()) && t.id.compareTo(a.id()) < 0)).orElse(true))
                .sorted(Comparator.comparing((Ticket t) -> t.createdAt).thenComparing(t -> t.id).reversed())
                .limit(filter.limit() + 1L)
                .map(this::domain)
                .toList();
    }

    @Override
    public List<StaffTicket> findQueue(QueueFilter filter) {
        return tickets.stream()
                .filter(t -> filter.statuses().isEmpty() || filter.statuses().contains(t.status))
                .filter(t -> filter.operatorId().map(operator -> operator.equals(t.operatorId)).orElse(true))
                .filter(t -> filter.orderNumber().isEmpty() || views.get(t.orderId).orderNumber() == filter.orderNumber().getAsLong())
                .filter(t -> filter.after().map(a -> t.createdAt.isAfter(a.createdAt())
                        || (t.createdAt.equals(a.createdAt()) && t.id.compareTo(a.id()) > 0)).orElse(true))
                .sorted(Comparator.comparing((Ticket t) -> t.createdAt).thenComparing(t -> t.id))
                .limit(filter.limit() + 1L)
                .map(this::staff)
                .toList();
    }

    @Override
    public Optional<StaffTicket> findStaffTicket(UUID ticketId) {
        return tickets.stream().filter(t -> t.id.equals(ticketId)).findFirst().map(this::staff);
    }

    /** Условное обновление, как {@code UPDATE ... WHERE status = 'created'}: обращение в другом статусе не меняется. */
    @Override
    public boolean takeTicket(UUID ticketId, UUID operatorId, Instant at) {
        for (Ticket ticket : tickets) {
            if (ticket.id.equals(ticketId) && "created".equals(ticket.status)) {
                ticket.status = "in_progress";
                ticket.operatorId = operatorId;
                ticket.takenAt = at;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean releaseTicket(UUID ticketId, UUID operatorId) {
        for (Ticket ticket : tickets) {
            if (ticket.id.equals(ticketId) && "in_progress".equals(ticket.status) && operatorId.equals(ticket.operatorId)) {
                ticket.status = "created";
                ticket.operatorId = null;
                ticket.takenAt = null;
                return true;
            }
        }
        return false;
    }

    /** Условное обновление, как {@code UPDATE ... WHERE status = 'in_progress' AND operator_id = ?}. */
    @Override
    public boolean markResend(UUID ticketId, UUID operatorId) {
        for (Ticket ticket : tickets) {
            if (ticket.id.equals(ticketId) && "in_progress".equals(ticket.status) && operatorId.equals(ticket.operatorId)) {
                ticket.action = "resend";
                return true;
            }
        }
        return false;
    }

    @Override
    public QueueDepth queueDepth() {
        long created = tickets.stream().filter(t -> "created".equals(t.status)).count();
        long inProgress = tickets.stream().filter(t -> "in_progress".equals(t.status)).count();
        Optional<Instant> oldest = tickets.stream().filter(t -> "created".equals(t.status)).map(t -> t.createdAt).min(Comparator.naturalOrder());
        return new QueueDepth(created, inProgress, oldest);
    }

    private StaffTicket staff(Ticket t) {
        OrderView view = views.get(t.orderId);
        return new StaffTicket(t.id, t.orderId, view.orderNumber(), t.buyerId, t.source, t.reason, t.status, Optional.ofNullable(t.operatorId),
                view.orderStatus(), view.issuedAt(), view.deliveryStatus(), t.createdAt, Optional.ofNullable(t.takenAt), Optional.ofNullable(t.resolvedAt),
                Optional.ofNullable(changes.get(t.id)).map(EmailChange::view));
    }

    private SupportTicket domain(Ticket t) {
        return new SupportTicket(t.id, t.orderId, t.buyerId, t.source, t.reason, t.status, t.createdAt, Optional.ofNullable(t.resolvedAt),
                Optional.ofNullable(t.resolvedBy), Optional.ofNullable(t.action), Optional.ofNullable(changes.get(t.id)).map(EmailChange::view));
    }

    /** Снимок обращений и этапов смены e-mail для отката транзакции в {@link FakeSupportTransaction}. */
    Snapshot snapshot() {
        return new Snapshot(List.copyOf(tickets), new LinkedHashMap<>(changes));
    }

    void restore(Snapshot snapshot) {
        tickets.clear();
        tickets.addAll(snapshot.tickets());
        changes.clear();
        changes.putAll(snapshot.changes());
    }

    /** Снимок состояния. */
    record Snapshot(List<Ticket> tickets, Map<UUID, EmailChange> changes) {
    }

    // ---- смена e-mail по обращению: те же условные переходы, что у UPDATE в базе ----

    @Override
    public Optional<EmailChange> find(UUID ticketId) {
        return Optional.ofNullable(changes.get(ticketId));
    }

    @Override
    public boolean start(UUID ticketId, UUID userId, String newEmail, String newEmailMasked, Instant at) {
        EmailChange old = changes.get(ticketId);
        if (old != null && (EmailChange.COMPLETED.equals(old.status()) || old.confirmedNotCompleted())) {
            return false;
        }
        changes.put(ticketId, new EmailChange(ticketId, userId, Optional.of(newEmail), newEmailMasked, EmailChange.PENDING, EmailChange.NOT_STARTED,
                EmailChange.AWAITING_PHONE_CODE, at, at));
        return true;
    }

    @Override
    public boolean phonePassed(UUID ticketId, Instant at) {
        EmailChange old = changes.get(ticketId);
        if (old == null || !EmailChange.AWAITING_PHONE_CODE.equals(old.status())) {
            return false;
        }
        changes.put(ticketId, new EmailChange(ticketId, old.userId(), old.newEmail(), old.newEmailMasked(), EmailChange.PASSED, EmailChange.PENDING,
                EmailChange.AWAITING_EMAIL_CODE, old.startedAt(), at));
        return true;
    }

    @Override
    public boolean newEmailPassed(UUID ticketId, Instant at) {
        EmailChange old = changes.get(ticketId);
        if (old == null || !EmailChange.AWAITING_EMAIL_CODE.equals(old.status()) || !EmailChange.PENDING.equals(old.newEmailCheck())) {
            return false;
        }
        changes.put(ticketId, new EmailChange(ticketId, old.userId(), old.newEmail(), old.newEmailMasked(), old.phoneCheck(), EmailChange.PASSED,
                old.status(), old.startedAt(), at));
        return true;
    }

    @Override
    public boolean completed(UUID ticketId, Instant at) {
        EmailChange old = changes.get(ticketId);
        if (old == null || !old.confirmedNotCompleted() || !EmailChange.PASSED.equals(old.phoneCheck())) {
            return false;
        }
        changes.put(ticketId, new EmailChange(ticketId, old.userId(), Optional.empty(), old.newEmailMasked(), old.phoneCheck(), old.newEmailCheck(),
                EmailChange.COMPLETED, old.startedAt(), at));
        return true;
    }

    @Override
    public boolean failConfirmed(UUID ticketId, Instant at) {
        EmailChange old = changes.get(ticketId);
        if (old == null || !old.confirmedNotCompleted() || !EmailChange.PASSED.equals(old.phoneCheck())) {
            return false;
        }
        changes.put(ticketId, new EmailChange(ticketId, old.userId(), Optional.empty(), old.newEmailMasked(), old.phoneCheck(), EmailChange.FAILED,
                EmailChange.FAILED, old.startedAt(), at));
        return true;
    }

    @Override
    public List<UUID> confirmedNotCompletedSince(Instant before, int limit) {
        return changes.values().stream().filter(c -> c.confirmedNotCompleted() && EmailChange.PASSED.equals(c.phoneCheck()) && !c.updatedAt().isAfter(before))
                .sorted(java.util.Comparator.comparing(EmailChange::updatedAt).thenComparing(EmailChange::ticketId)).map(EmailChange::ticketId).limit(limit)
                .toList();
    }

    @Override
    public boolean markCodeCheckPassed(UUID ticketId) {
        for (Ticket ticket : tickets) {
            if (ticket.id.equals(ticketId)) {
                ticket.codeCheckResult = "passed";
                return true;
            }
        }
        return false;
    }

    /** Результат проверки кода у обращения; пусто, если его ещё не записывали. */
    public Optional<String> codeCheckOf(UUID ticketId) {
        return tickets.stream().filter(t -> t.id.equals(ticketId)).map(t -> t.codeCheckResult).filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public boolean fail(UUID ticketId, String phoneCheck, String newEmailCheck, Instant at) {
        EmailChange old = changes.get(ticketId);
        boolean waiting = old != null && (EmailChange.AWAITING_PHONE_CODE.equals(old.status()) || EmailChange.AWAITING_EMAIL_CODE.equals(old.status()));
        if (!waiting || EmailChange.PASSED.equals(old.newEmailCheck())) {
            return false;
        }
        changes.put(ticketId, new EmailChange(ticketId, old.userId(), Optional.empty(), old.newEmailMasked(), phoneCheck, newEmailCheck, EmailChange.FAILED,
                old.startedAt(), at));
        return true;
    }

    /** Как вторая часть {@code resolveOpenTickets} в базе: смена, ждущая кода, обрывается, новый адрес обнуляется. */
    private void abandon(UUID ticketId, Instant at) {
        EmailChange old = changes.get(ticketId);
        boolean waiting = old != null && (EmailChange.AWAITING_PHONE_CODE.equals(old.status()) || EmailChange.AWAITING_EMAIL_CODE.equals(old.status()));
        if (waiting && !EmailChange.PASSED.equals(old.newEmailCheck())) {
            changes.put(ticketId, new EmailChange(ticketId, old.userId(), Optional.empty(), old.newEmailMasked(),
                    EmailChange.PENDING.equals(old.phoneCheck()) ? EmailChange.FAILED : old.phoneCheck(),
                    EmailChange.PENDING.equals(old.newEmailCheck()) ? EmailChange.FAILED : old.newEmailCheck(), EmailChange.FAILED, old.startedAt(), at));
        }
    }

    @Override
    public boolean markEmailChange(UUID ticketId, UUID operatorId) {
        for (Ticket ticket : tickets) {
            if (ticket.id.equals(ticketId) && "in_progress".equals(ticket.status) && operatorId.equals(ticket.operatorId)) {
                ticket.action = "email_change";
                return true;
            }
        }
        return false;
    }

    /** Состояние смены обращения как в таблице (для проверок в тестах). */
    public Optional<EmailChange> changeOf(UUID ticketId) {
        return find(ticketId);
    }

    /** Состояние для ответа (без адреса). */
    public Optional<EmailChangeView> viewOf(UUID ticketId) {
        return find(ticketId).map(EmailChange::view);
    }

    /** Закрывает обращение как решённое оператором: так в тестах появляется «решено», после которого новое обращение возможно. */
    public void resolveByOperator(UUID ticketId, String action, Instant at) {
        for (Ticket ticket : tickets) {
            if (ticket.id.equals(ticketId)) {
                ticket.status = "resolved";
                ticket.resolvedBy = "operator";
                ticket.action = action;
                ticket.resolvedAt = at;
            }
        }
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
