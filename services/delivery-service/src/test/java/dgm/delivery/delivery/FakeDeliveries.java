package dgm.delivery.delivery;

import dgm.delivery.delivery.domain.DeliveryState;
import dgm.delivery.delivery.domain.DueWatch;
import dgm.delivery.delivery.domain.QueuedDelivery;
import dgm.delivery.delivery.domain.SentDelivery;
import dgm.delivery.delivery.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Выдачи в памяти для модульных тестов. Повторяет правила SQL из {@code JdbcDeliveryRepository}: одна первичная выдача на заказ, не больше одной
 * выдачи в очереди на заказ (повторная создаётся только при первичной и без очереди), выборка «в очереди, срок наступил, сначала старые», аренда
 * сдвигает срок, исход пишется только если число выполненных попыток совпало.
 */
public final class FakeDeliveries implements DeliveryRepository {

    /** Строка выдачи. */
    public static final class Row {
        public final UUID id;
        public final UUID orderId;
        public final UUID buyerId;
        public final String kind;
        public final String title;
        public final int quantity;
        public String address;
        public int attemptNo;
        public String status = "queued";
        public Instant nextAttemptAt;
        public String lastErrorCode;
        public String failReason;
        public Instant sentAt;
        public Instant deliveredAt;

        Row(UUID id, UUID orderId, UUID buyerId, String kind, String address, String title, int quantity, Instant nextAttemptAt) {
            this.id = id;
            this.orderId = orderId;
            this.buyerId = buyerId;
            this.kind = kind;
            this.address = address;
            this.title = title;
            this.quantity = quantity;
            this.nextAttemptAt = nextAttemptAt;
        }
    }

    /** Запись попытки. */
    public record Attempt(UUID deliveryId, int attemptNo, String outcome, String errorCode, Instant startedAt, Instant finishedAt) {
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();
    private final Map<UUID, Instant> watches = new LinkedHashMap<>();
    private final Map<UUID, String> watchStatuses = new LinkedHashMap<>();
    private final Map<String, Instant> providerStatuses = new LinkedHashMap<>();
    private final List<Attempt> attempts = new ArrayList<>();
    private final List<Duration> leases = new ArrayList<>();
    private final List<Integer> limits = new ArrayList<>();
    private final List<Integer> watchLimits = new ArrayList<>();

    /** Если задано, любая запись исхода бросает это исключение (сбой базы между отправкой и записью). */
    public RuntimeException failOnMark;

    @Override
    public boolean insertPrimary(UUID id, UUID orderId, UUID buyerId, String address, String productTitle, int quantity, Instant now) {
        for (Row row : rows.values()) {
            if (row.orderId.equals(orderId) && "primary".equals(row.kind)) {
                return false;
            }
        }
        rows.put(id, new Row(id, orderId, buyerId, "primary", address, productTitle, quantity, now));
        return true;
    }

    @Override
    public boolean insertRepeat(UUID id, UUID orderId, String address, Instant now) {
        Row primary = rows.values().stream().filter(r -> r.orderId.equals(orderId) && "primary".equals(r.kind)).findFirst().orElse(null);
        if (primary == null || rows.values().stream().anyMatch(r -> r.orderId.equals(orderId) && "queued".equals(r.status))) {
            return false;
        }
        rows.put(id, new Row(id, orderId, primary.buyerId, "repeat", address, primary.title, primary.quantity, now));
        return true;
    }

    @Override
    public boolean insertWatch(UUID orderId, Instant deadline, Instant now) {
        boolean created = watches.putIfAbsent(orderId, deadline) == null;
        if (created) {
            watchStatuses.put(orderId, "open");
        }
        return created;
    }

    @Override
    public List<QueuedDelivery> claimDue(Instant now, Duration lease, int limit) {
        leases.add(lease);
        limits.add(limit);
        List<QueuedDelivery> claimed = new ArrayList<>();
        rows.values().stream()
                .filter(r -> "queued".equals(r.status) && !r.nextAttemptAt.isAfter(now))
                .sorted(Comparator.comparing((Row r) -> r.nextAttemptAt).thenComparing(r -> r.id))
                .limit(limit)
                .forEach(r -> {
                    r.nextAttemptAt = now.plus(lease);
                    claimed.add(new QueuedDelivery(r.id, r.orderId, r.buyerId, r.kind, r.address, r.title, r.quantity, r.attemptNo));
                });
        return claimed;
    }

    @Override
    public boolean markSent(UUID id, int expectedAttempts, Instant startedAt, Instant finishedAt) {
        failIfAsked();
        Row row = rows.get(id);
        if (row == null || !"queued".equals(row.status) || row.attemptNo != expectedAttempts) {
            return false;
        }
        row.status = "sent";
        row.attemptNo = expectedAttempts + 1;
        row.sentAt = finishedAt;
        row.nextAttemptAt = null;
        row.lastErrorCode = null;
        attempts.add(new Attempt(id, row.attemptNo, "accepted", null, startedAt, finishedAt));
        return true;
    }

    @Override
    public boolean markRetry(UUID id, int expectedAttempts, String errorCode, Instant nextAttemptAt, Instant startedAt, Instant finishedAt) {
        failIfAsked();
        Row row = rows.get(id);
        if (row == null || !"queued".equals(row.status) || row.attemptNo != expectedAttempts) {
            return false;
        }
        row.attemptNo = expectedAttempts + 1;
        row.nextAttemptAt = nextAttemptAt;
        row.lastErrorCode = errorCode;
        attempts.add(new Attempt(id, row.attemptNo, "retryable_error", errorCode, startedAt, finishedAt));
        return true;
    }

    @Override
    public boolean markFailed(UUID id, int expectedAttempts, String outcome, String errorCode, String failReason, Instant startedAt, Instant finishedAt) {
        failIfAsked();
        Row row = rows.get(id);
        if (row == null || !"queued".equals(row.status) || row.attemptNo != expectedAttempts) {
            return false;
        }
        row.status = "failed";
        row.attemptNo = expectedAttempts + 1;
        row.nextAttemptAt = null;
        row.failReason = failReason;
        row.lastErrorCode = errorCode;
        attempts.add(new Attempt(id, row.attemptNo, outcome, errorCode, startedAt, finishedAt));
        return true;
    }

    @Override
    public int anonymizeOpen(UUID buyerId, String replacement) {
        int changed = 0;
        for (Row row : rows.values()) {
            if (row.buyerId.equals(buyerId) && ("queued".equals(row.status) || "sent".equals(row.status)) && !replacement.equals(row.address)) {
                row.address = replacement;
                changed++;
            }
        }
        return changed;
    }

    @Override
    public Optional<DeliveryState> find(UUID id) {
        return Optional.ofNullable(rows.get(id)).map(FakeDeliveries::state);
    }

    @Override
    public Optional<DeliveryState> primaryOf(UUID orderId) {
        return rows.values().stream().filter(r -> r.orderId.equals(orderId) && "primary".equals(r.kind)).findFirst().map(FakeDeliveries::state);
    }

    private static DeliveryState state(Row r) {
        return new DeliveryState(r.id, r.orderId, r.kind, r.status, r.attemptNo);
    }

    @Override
    public boolean recordProviderStatus(UUID deliveryId, String providerStatus, Instant occurredAt, Instant now) {
        return providerStatuses.putIfAbsent(deliveryId + "/" + providerStatus, occurredAt) == null;
    }

    @Override
    public boolean markDelivered(UUID id, Instant deliveredAt) {
        Row row = rows.get(id);
        if (row == null || !"sent".equals(row.status)) {
            return false;
        }
        row.status = "delivered";
        row.deliveredAt = deliveredAt;
        return true;
    }

    @Override
    public boolean markUndelivered(UUID id, String failReason, String errorCode) {
        Row row = rows.get(id);
        if (row == null || !"sent".equals(row.status)) {
            return false;
        }
        row.status = "failed";
        row.failReason = failReason;
        row.lastErrorCode = errorCode;
        return true;
    }

    @Override
    public Optional<Instant> watchDeadline(UUID orderId) {
        return Optional.ofNullable(watches.get(orderId));
    }

    @Override
    public boolean closeWatch(UUID orderId, Instant now) {
        String status = watchStatuses.get(orderId);
        if (status == null || "closed".equals(status)) {
            return false;
        }
        watchStatuses.put(orderId, "closed");
        return true;
    }

    @Override
    public List<DueWatch> lockDueWatches(Instant now, int limit) {
        watchLimits.add(limit);
        return watches.entrySet().stream()
                .filter(e -> "open".equals(watchStatuses.get(e.getKey())) && !e.getValue().isAfter(now))
                .sorted(Map.Entry.<UUID, Instant>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> new DueWatch(e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public boolean handOver(UUID orderId, Instant now) {
        if (!"open".equals(watchStatuses.get(orderId))) {
            return false;
        }
        watchStatuses.put(orderId, "handed_over");
        return true;
    }

    @Override
    public List<SentDelivery> sentBefore(Instant sentBefore, SentDelivery after, int limit) {
        Comparator<SentDelivery> order = Comparator.comparing(SentDelivery::sentAt).thenComparing(SentDelivery::id);
        return rows.values().stream()
                .filter(r -> "sent".equals(r.status) && !r.sentAt.isAfter(sentBefore))
                .map(r -> new SentDelivery(r.id, r.sentAt))
                .filter(d -> after == null || order.compare(d, after) > 0)
                .sorted(order)
                .limit(limit)
                .toList();
    }

    private void failIfAsked() {
        if (failOnMark != null) {
            throw failOnMark;
        }
    }

    public Row row(UUID id) {
        return rows.get(id);
    }

    public List<Row> rows() {
        return new ArrayList<>(rows.values());
    }

    public List<Attempt> attempts() {
        return attempts;
    }

    public Map<UUID, Instant> watches() {
        return watches;
    }

    /** Состояние контроля заказа: open, handed_over или closed; {@code null}, если контроля нет. */
    public String watchStatus(UUID orderId) {
        return watchStatuses.get(orderId);
    }

    /** Принятые статусы провайдера в виде «выдача/статус». */
    public java.util.Set<String> providerStatuses() {
        return providerStatuses.keySet();
    }

    /** Выдача «отправлено» для тестов статусов: как после принятия письма провайдером. */
    public Row addSent(UUID id, UUID orderId, UUID buyerId, String address, String title, int quantity, int attemptNo, Instant sentAt) {
        Row row = new Row(id, orderId, buyerId, "primary", address, title, quantity, null);
        row.status = "sent";
        row.attemptNo = attemptNo;
        row.sentAt = sentAt;
        rows.put(id, row);
        return row;
    }

    /** Повторная выдача «отправлено» для тестов статусов. */
    public Row addSentRepeat(UUID id, UUID orderId, UUID buyerId, String address, String title, int quantity, int attemptNo, Instant sentAt) {
        Row row = new Row(id, orderId, buyerId, "repeat", address, title, quantity, null);
        row.status = "sent";
        row.attemptNo = attemptNo;
        row.sentAt = sentAt;
        rows.put(id, row);
        return row;
    }

    public List<Duration> leases() {
        return leases;
    }

    public List<Integer> limits() {
        return limits;
    }

    /** Размеры пачек, с которыми контроль 30 минут просил открытые контроли. */
    public List<Integer> watchLimits() {
        return watchLimits;
    }
}
