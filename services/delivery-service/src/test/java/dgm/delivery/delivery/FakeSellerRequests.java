package dgm.delivery.delivery;

import dgm.delivery.delivery.domain.ExpiredRequest;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerRequestSeed;
import dgm.delivery.delivery.domain.SellerRequestState;
import dgm.delivery.delivery.repository.SellerRequestRepository;
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
 * Запросы продавцам в памяти для модульных тестов. Повторяет условия SQL из {@code JdbcSellerRequestRepository}: выборка «запрошен, срок наступил»,
 * аренда сдвигает срок, все изменения условные по состоянию, аренда на приём ответа не выдаётся второй раз и при вышедшем сроке, выбор просроченных
 * пропускает запросы с действующей арендой. Заказ и число ключей берутся из выдач ({@link FakeDeliveries}).
 */
public final class FakeSellerRequests implements SellerRequestRepository {

    /** Строка запроса. */
    public static final class Row {
        public final UUID deliveryId;
        public final UUID requestId;
        public final UUID sellerId;
        public final UUID productId;
        public final long orderNumber;
        public final Instant createdAt;
        public final Instant deadlineAt;
        public String status = "requested";
        public int attempts;
        public Instant nextAttemptAt;
        public Instant leaseUntil;
        public String errorCode;
        public Instant answeredAt;

        Row(UUID deliveryId, UUID requestId, SellerRequestSeed seed, Instant deadlineAt, Instant now) {
            this.deliveryId = deliveryId;
            this.requestId = requestId;
            this.sellerId = seed.sellerId();
            this.productId = seed.productId();
            this.orderNumber = seed.orderNumber();
            this.createdAt = now;
            this.deadlineAt = deadlineAt;
            this.nextAttemptAt = now;
        }
    }

    private final FakeDeliveries deliveries;
    private final Map<UUID, Row> rows = new LinkedHashMap<>();

    public FakeSellerRequests(FakeDeliveries deliveries) {
        this.deliveries = deliveries;
    }

    @Override
    public boolean insert(UUID deliveryId, UUID requestId, SellerRequestSeed seed, Instant deadlineAt, Instant now) {
        if (!deadlineAt.isAfter(now)) {
            throw new IllegalStateException("ck_seller_request_deadline: срок должен быть позже создания запроса");
        }
        return rows.putIfAbsent(deliveryId, new Row(deliveryId, requestId, seed, deadlineAt, now)) == null;
    }

    @Override
    public List<PendingRequest> claimDue(Instant now, Duration lease, int limit) {
        List<PendingRequest> claimed = new ArrayList<>();
        rows.values().stream().filter(r -> "requested".equals(r.status) && r.nextAttemptAt != null && !r.nextAttemptAt.isAfter(now))
                .sorted(Comparator.comparing((Row r) -> r.nextAttemptAt).thenComparing(r -> r.deliveryId)).limit(limit).forEach(r -> {
                    FakeDeliveries.Row delivery = deliveries.row(r.deliveryId);
                    claimed.add(new PendingRequest(r.deliveryId, r.requestId, delivery.orderId, r.sellerId, r.productId, r.orderNumber, delivery.quantity,
                            r.createdAt, r.deadlineAt, r.attempts));
                    r.nextAttemptAt = now.plus(lease);
                });
        return claimed;
    }

    @Override
    public boolean accept(UUID deliveryId, int expectedAttempts, Instant now) {
        Row row = rows.get(deliveryId);
        if (row == null || !"requested".equals(row.status) || row.attempts != expectedAttempts) {
            return false;
        }
        row.status = "accepted";
        row.attempts = expectedAttempts + 1;
        row.nextAttemptAt = null;
        return true;
    }

    @Override
    public boolean retry(UUID deliveryId, int expectedAttempts, Instant nextAttemptAt) {
        Row row = rows.get(deliveryId);
        if (row == null || !"requested".equals(row.status) || row.attempts != expectedAttempts) {
            return false;
        }
        if (nextAttemptAt != null && expectedAttempts + 1 >= 3) {
            throw new IllegalStateException("ck_seller_request_next_attempt: после третьей отправки следующей нет");
        }
        row.attempts = expectedAttempts + 1;
        row.nextAttemptAt = nextAttemptAt;
        return true;
    }

    @Override
    public boolean fail(UUID deliveryId, Integer attemptsAfter, String errorCode, String errorMessage) {
        Row row = rows.get(deliveryId);
        if (row == null || !open(row)) {
            return false;
        }
        row.status = "failed";
        if (attemptsAfter != null) {
            row.attempts = attemptsAfter;
        }
        row.nextAttemptAt = null;
        row.leaseUntil = null;
        row.errorCode = errorCode;
        return true;
    }

    @Override
    public boolean answered(UUID requestId, Instant now) {
        Row row = byRequest(requestId);
        if (row == null || !open(row)) {
            return false;
        }
        row.status = "answered";
        row.answeredAt = now;
        row.nextAttemptAt = null;
        row.leaseUntil = null;
        return true;
    }

    @Override
    public boolean timeout(UUID deliveryId) {
        Row row = rows.get(deliveryId);
        if (row == null || !open(row)) {
            return false;
        }
        row.status = "timeout";
        row.nextAttemptAt = null;
        row.leaseUntil = null;
        return true;
    }

    @Override
    public Optional<SellerRequestState> find(UUID requestId) {
        Row row = byRequest(requestId);
        if (row == null) {
            return Optional.empty();
        }
        FakeDeliveries.Row delivery = deliveries.row(row.deliveryId);
        return Optional.of(new SellerRequestState(row.deliveryId, row.requestId, delivery.orderId, row.sellerId, delivery.quantity, row.status, row.createdAt,
                row.deadlineAt, row.leaseUntil, row.errorCode));
    }

    @Override
    public boolean lease(UUID requestId, Instant now, Instant leaseUntil) {
        Row row = byRequest(requestId);
        if (row == null || !open(row) || !row.deadlineAt.isAfter(now) || (row.leaseUntil != null && row.leaseUntil.isAfter(now))) {
            return false;
        }
        row.leaseUntil = leaseUntil;
        return true;
    }

    @Override
    public void release(UUID requestId) {
        Row row = byRequest(requestId);
        if (row != null && open(row)) {
            row.leaseUntil = null;
        }
    }

    @Override
    public List<ExpiredRequest> lockExpired(Instant now, int limit) {
        return rows.values().stream().filter(r -> open(r) && !r.deadlineAt.isAfter(now) && (r.leaseUntil == null || !r.leaseUntil.isAfter(now)))
                .sorted(Comparator.comparing((Row r) -> r.deadlineAt).thenComparing(r -> r.deliveryId)).limit(limit)
                .map(r -> new ExpiredRequest(r.deliveryId, r.requestId, deliveries.row(r.deliveryId).orderId, r.sellerId, r.createdAt)).toList();
    }

    public Row row(UUID deliveryId) {
        return rows.get(deliveryId);
    }

    public List<Row> rows() {
        return List.copyOf(rows.values());
    }

    private static boolean open(Row row) {
        return "requested".equals(row.status) || "accepted".equals(row.status);
    }

    private Row byRequest(UUID requestId) {
        return rows.values().stream().filter(r -> r.requestId.equals(requestId)).findFirst().orElse(null);
    }
}
