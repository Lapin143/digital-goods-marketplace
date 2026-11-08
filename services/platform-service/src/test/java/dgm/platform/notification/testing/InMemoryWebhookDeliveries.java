package dgm.platform.notification.testing;

import dgm.platform.notification.domain.NewWebhookDelivery;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.WebhookDelivery;
import dgm.platform.notification.domain.WebhookDeliveryFilter;
import dgm.platform.notification.repository.WebhookDeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Очередь webhook в памяти для модульных тестов: те же правила, что у таблицы {@code notification.webhook_delivery} (уникальный {@code event_id},
 * переходы только из «в очереди», шесть попыток у {@code failed}, ноль у {@code skipped}, срок есть только у уведомления в очереди), без базы.
 */
public final class InMemoryWebhookDeliveries implements WebhookDeliveryRepository {

    /** Строка таблицы. */
    public static final class Row {
        public final UUID id;
        public final UUID sellerId;
        public final UUID eventId;
        public final String eventType;
        public final long orderNumber;
        public final Map<String, Object> payload;
        public final Instant createdAt;
        public String status = "queued";
        public int attempts;
        public Instant nextAttemptAt;
        public Instant lastAttemptAt;
        public Integer lastResponseCode;
        public String lastErrorCode;
        public Instant sentAt;

        Row(NewWebhookDelivery d) {
            this.id = d.id();
            this.sellerId = d.sellerId();
            this.eventId = d.eventId();
            this.eventType = d.eventType();
            this.orderNumber = d.orderNumber();
            this.payload = d.payload();
            this.createdAt = d.createdAt();
            this.nextAttemptAt = d.createdAt();
        }
    }

    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    public List<Row> rows() {
        return List.copyOf(rows.values());
    }

    public Row only() {
        if (rows.size() != 1) {
            throw new AssertionError("Ожидалась одна строка, а их " + rows.size());
        }
        return rows.values().iterator().next();
    }

    @Override
    public boolean insert(NewWebhookDelivery delivery) {
        if (rows.values().stream().anyMatch(r -> r.eventId.equals(delivery.eventId()))) {
            return false;
        }
        rows.put(delivery.id(), new Row(delivery));
        return true;
    }

    @Override
    public List<PendingWebhook> claimDue(Instant now, Duration lease, int limit) {
        List<Row> due = rows.values().stream().filter(r -> r.status.equals("queued") && !r.nextAttemptAt.isAfter(now))
                .sorted(Comparator.comparing((Row r) -> r.nextAttemptAt).thenComparing(r -> r.id)).limit(limit).toList();
        List<PendingWebhook> claimed = new ArrayList<>();
        for (Row row : due) {
            row.nextAttemptAt = now.plus(lease);
            claimed.add(new PendingWebhook(row.id, row.sellerId, row.eventId, row.eventType, dgm.kit.json.Json.write(row.payload), row.attempts));
        }
        return claimed;
    }

    @Override
    public boolean markSent(UUID id, int attempts, int responseCode, Instant at) {
        Row row = rows.get(id);
        if (row == null || !row.status.equals("queued")) {
            return false;
        }
        row.status = "sent";
        row.attempts = attempts;
        row.lastAttemptAt = at;
        row.lastResponseCode = responseCode;
        row.lastErrorCode = null;
        row.sentAt = at;
        row.nextAttemptAt = null;
        return true;
    }

    @Override
    public boolean reschedule(UUID id, int attempts, Instant nextAttemptAt, Integer responseCode, String errorCode, Instant at) {
        Row row = rows.get(id);
        if (row == null || !row.status.equals("queued")) {
            return false;
        }
        row.attempts = attempts;
        row.nextAttemptAt = nextAttemptAt;
        row.lastAttemptAt = at;
        row.lastResponseCode = responseCode;
        row.lastErrorCode = errorCode;
        return true;
    }

    @Override
    public boolean markFailed(UUID id, int attempts, Integer responseCode, String errorCode, Instant at) {
        Row row = rows.get(id);
        if (row == null || !row.status.equals("queued")) {
            return false;
        }
        if (attempts != 6) {
            throw new IllegalStateException("ck_webhook_delivery_failed_attempts: у failed ровно шесть попыток");
        }
        row.status = "failed";
        row.attempts = attempts;
        row.lastAttemptAt = at;
        row.lastResponseCode = responseCode;
        row.lastErrorCode = errorCode;
        row.nextAttemptAt = null;
        return true;
    }

    @Override
    public boolean markSkipped(UUID id, String reason) {
        Row row = rows.get(id);
        if (row == null || !row.status.equals("queued")) {
            return false;
        }
        row.status = "skipped";
        row.attempts = 0;
        row.lastErrorCode = reason;
        row.nextAttemptAt = null;
        return true;
    }

    @Override
    public List<WebhookDelivery> findBySeller(WebhookDeliveryFilter filter) {
        return rows.values().stream().filter(r -> r.sellerId.equals(filter.sellerId()))
                .filter(r -> filter.statuses().isEmpty() || filter.statuses().get().contains(r.status))
                .filter(r -> filter.after().isEmpty() || before(r, filter.after().get()))
                .sorted(Comparator.comparing((Row r) -> r.createdAt).thenComparing(r -> r.id).reversed())
                .limit(filter.limit() + 1L)
                .map(r -> new WebhookDelivery(r.id, r.eventId, r.eventType, r.orderNumber, r.status, r.attempts, r.lastResponseCode, r.lastAttemptAt,
                        r.nextAttemptAt, r.createdAt))
                .toList();
    }

    private static boolean before(Row row, WebhookDeliveryFilter.After after) {
        int byTime = row.createdAt.compareTo(after.createdAt());
        return byTime < 0 || byTime == 0 && row.id.compareTo(after.id()) < 0;
    }
}
