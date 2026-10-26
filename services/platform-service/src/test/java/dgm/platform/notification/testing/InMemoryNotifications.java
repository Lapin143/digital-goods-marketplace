package dgm.platform.notification.testing;

import dgm.platform.notification.domain.NewNotification;
import dgm.platform.notification.domain.Notification;
import dgm.platform.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Очередь уведомлений в памяти с теми же правилами, что у таблицы: уникальная пара «событие, шаблон», выбор по сроку, аренда, переходы статусов. */
public final class InMemoryNotifications implements NotificationRepository {

    /** Строка таблицы. */
    public static final class Row {
        public final NewNotification source;
        public String status = "queued";
        public int attempts;
        public Instant nextAttemptAt;
        public String lastErrorCode;
        public boolean inDeadQueue;
        public Instant sentAt;

        Row(NewNotification source) {
            this.source = source;
            this.nextAttemptAt = source.createdAt();
        }
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    /** Принятые статусы провайдера: пара «уведомление, статус» уникальна, как в таблице. */
    private final Set<String> providerStatuses = new LinkedHashSet<>();

    @Override
    public boolean insert(NewNotification notification) {
        if (notification.sourceEventId() != null && rows.values().stream().anyMatch(r -> notification.sourceEventId().equals(r.source.sourceEventId())
                && notification.template().equals(r.source.template()) && notification.userId().equals(r.source.userId()))) {
            return false;
        }
        rows.put(notification.id(), new Row(notification));
        return true;
    }

    @Override
    public Optional<Notification> claim(Instant now, Instant leaseUntil) {
        Optional<Row> due = rows.values().stream().filter(r -> "queued".equals(r.status) && !r.nextAttemptAt.isAfter(now))
                .min(Comparator.comparing((Row r) -> r.nextAttemptAt).thenComparing(r -> r.source.id()));
        due.ifPresent(r -> r.nextAttemptAt = leaseUntil);
        return due.map(InMemoryNotifications::view);
    }

    @Override
    public Optional<Notification> find(UUID id) {
        return Optional.ofNullable(rows.get(id)).map(InMemoryNotifications::view);
    }

    @Override
    public boolean markSent(UUID id, int attempts, Instant at) {
        Row row = rows.get(id);
        if (row == null || !"queued".equals(row.status)) {
            return false;
        }
        row.status = "sent";
        row.attempts = attempts;
        row.sentAt = at;
        row.nextAttemptAt = null;
        row.lastErrorCode = null;
        if (providerStatuses.contains(id + ":delivered")) {
            row.status = "delivered";
        }
        return true;
    }

    @Override
    public boolean reschedule(UUID id, int attempts, Instant nextAttemptAt, String errorCode, Instant at) {
        Row row = rows.get(id);
        if (row == null || !"queued".equals(row.status)) {
            return false;
        }
        row.attempts = attempts;
        row.nextAttemptAt = nextAttemptAt;
        row.lastErrorCode = errorCode;
        return true;
    }

    @Override
    public boolean markFailed(UUID id, int attempts, String errorCode, boolean deadQueue, Instant at) {
        Row row = rows.get(id);
        if (row == null || !"queued".equals(row.status)) {
            return false;
        }
        row.status = "failed";
        row.attempts = attempts;
        row.lastErrorCode = errorCode;
        row.inDeadQueue = deadQueue;
        row.nextAttemptAt = null;
        return true;
    }

    @Override
    public boolean recordProviderStatus(UUID id, String providerStatus, Instant occurredAt, Instant at) {
        return rows.containsKey(id) && providerStatuses.add(id + ":" + providerStatus);
    }

    @Override
    public boolean markDelivered(UUID id, Instant at) {
        Row row = rows.get(id);
        if (row == null || !"sent".equals(row.status)) {
            return false;
        }
        row.status = "delivered";
        return true;
    }

    @Override
    public Optional<Notification> markFailedByProvider(UUID id, String errorCode, Instant at) {
        Row row = rows.get(id);
        if (row == null || !("queued".equals(row.status) || "sent".equals(row.status))) {
            return Optional.empty();
        }
        row.status = "failed";
        row.attempts = Math.max(row.attempts, 1);
        row.lastErrorCode = errorCode;
        row.inDeadQueue = true;
        row.nextAttemptAt = null;
        return Optional.of(view(row));
    }

    /** Принятые статусы провайдера по уведомлению, например {@code accepted}, {@code delivered}. */
    public Set<String> providerStatuses(UUID id) {
        Set<String> statuses = new LinkedHashSet<>();
        String prefix = id + ":";
        providerStatuses.stream().filter(s -> s.startsWith(prefix)).forEach(s -> statuses.add(s.substring(prefix.length())));
        return statuses;
    }

    public Row row(UUID id) {
        return rows.get(id);
    }

    public List<Row> all() {
        return new ArrayList<>(rows.values());
    }

    private static Notification view(Row row) {
        NewNotification n = row.source;
        return new Notification(n.id(), n.userId(), n.template(), Notification.EMAIL, n.params(), Optional.ofNullable(n.sourceEventId()),
                row.attempts, n.createdAt());
    }
}
