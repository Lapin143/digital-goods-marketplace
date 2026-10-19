package dgm.delivery.delivery;

import dgm.delivery.delivery.domain.QueuedDelivery;
import dgm.delivery.delivery.repository.DeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Выдачи в памяти для модульных тестов. Повторяет правила SQL из {@code JdbcDeliveryRepository}: одна первичная выдача на заказ, выборка
 * «в очереди, срок наступил, сначала старые», аренда сдвигает срок, исход пишется только если число выполненных попыток совпало.
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
    private final List<Attempt> attempts = new ArrayList<>();
    private final List<Duration> leases = new ArrayList<>();
    private final List<Integer> limits = new ArrayList<>();

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

    /** Повторная выдача для тестов (создаётся по обращению, в шаге 13 такого пути ещё нет). */
    public Row addRepeat(UUID id, UUID orderId, UUID buyerId, String address, String productTitle, int quantity, Instant due) {
        Row row = new Row(id, orderId, buyerId, "repeat", address, productTitle, quantity, due);
        rows.put(id, row);
        return row;
    }

    @Override
    public boolean insertWatch(UUID orderId, Instant deadline, Instant now) {
        return watches.putIfAbsent(orderId, deadline) == null;
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

    public List<Duration> leases() {
        return leases;
    }

    public List<Integer> limits() {
        return limits;
    }
}
