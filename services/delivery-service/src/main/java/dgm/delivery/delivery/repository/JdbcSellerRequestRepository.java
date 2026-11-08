package dgm.delivery.delivery.repository;

import dgm.delivery.delivery.domain.ExpiredRequest;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerRequestSeed;
import dgm.delivery.delivery.domain.SellerRequestState;
import dgm.kit.boot.ModuleDatabases;
import dgm.kit.jdbc.RowClaim;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Запросы продавцам в PostgreSQL под ролью {@code app_delivery} (модуль {@code delivery}). */
@Component
public final class JdbcSellerRequestRepository implements SellerRequestRepository {

    /** Очередь отправки: «запрошен» с наступившим сроком (ix_seller_request_dispatch); аренда сдвигает {@code next_attempt_at}. */
    private static final RowClaim DUE = RowClaim.on("delivery.seller_request")
            .idColumn("delivery_id")
            .where("status = 'requested' and next_attempt_at is not null")
            .dueColumn("next_attempt_at")
            .orderBy("next_attempt_at, delivery_id")
            .returning("delivery_id, request_id, seller_id, product_id, order_number, created_at, deadline_at, attempts")
            .build();

    /** Заказ и число ключей в таблице запросов не хранятся: сначала пустышки, их заменяет {@link #withDelivery}. */
    private static final UUID NIL = new UUID(0L, 0L);

    private static final RowMapper<PendingRequest> CLAIMED = (rs, row) -> new PendingRequest(rs.getObject("delivery_id", UUID.class),
            rs.getObject("request_id", UUID.class), NIL, rs.getObject("seller_id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getLong("order_number"), 1, rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("deadline_at", OffsetDateTime.class).toInstant(), rs.getInt("attempts"));

    private static final RowMapper<SellerRequestState> STATE = (rs, row) -> new SellerRequestState(rs.getObject("delivery_id", UUID.class),
            rs.getObject("request_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getInt("quantity"),
            rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("deadline_at", OffsetDateTime.class).toInstant(), instant(rs.getObject("lease_until", OffsetDateTime.class)), rs.getString("error_code"));

    private static final RowMapper<ExpiredRequest> EXPIRED = (rs, row) -> new ExpiredRequest(rs.getObject("delivery_id", UUID.class),
            rs.getObject("request_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getObject("seller_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public JdbcSellerRequestRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcDeliveryRepository.MODULE);
    }

    @Override
    public boolean insert(UUID deliveryId, UUID requestId, SellerRequestSeed seed, Instant deadlineAt, Instant now) {
        return jdbc.update("insert into delivery.seller_request (delivery_id, request_id, seller_id, product_id, order_number, status, deadline_at, attempts, "
                + "next_attempt_at, created_at, updated_at) values (?, ?, ?, ?, ?, 'requested', ?, 0, ?, ?, ?) on conflict do nothing",
                deliveryId, requestId, seed.sellerId(), seed.productId(), seed.orderNumber(), time(deadlineAt), time(now), time(now), time(now)) == 1;
    }

    @Override
    public List<PendingRequest> claimDue(Instant now, Duration lease, int limit) {
        return DUE.claim(jdbc, now, lease, limit, CLAIMED).stream().map(this::withDelivery).toList();
    }

    /** Заказ и число ключей берутся из выдачи: запрос продавцу их знает только по ней. */
    private PendingRequest withDelivery(PendingRequest claimed) {
        return jdbc.query("select order_id, quantity from delivery.delivery where id = ?",
                (rs, row) -> claimed.withDelivery(rs.getObject("order_id", UUID.class), rs.getInt("quantity")), claimed.deliveryId()).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("У запроса " + claimed.requestId() + " нет выдачи"));
    }

    @Override
    public boolean accept(UUID deliveryId, int expectedAttempts, Instant now) {
        return jdbc.update("update delivery.seller_request set status = 'accepted', attempts = ?, next_attempt_at = null, accepted_at = ? "
                + "where delivery_id = ? and status = 'requested' and attempts = ?", expectedAttempts + 1, time(now), deliveryId, expectedAttempts) == 1;
    }

    @Override
    public boolean retry(UUID deliveryId, int expectedAttempts, Instant nextAttemptAt) {
        return jdbc.update("update delivery.seller_request set attempts = ?, next_attempt_at = ? where delivery_id = ? and status = 'requested' "
                + "and attempts = ?", expectedAttempts + 1, nextAttemptAt == null ? null : time(nextAttemptAt), deliveryId, expectedAttempts) == 1;
    }

    @Override
    public boolean fail(UUID deliveryId, Integer attemptsAfter, String errorCode, String errorMessage) {
        return jdbc.update("update delivery.seller_request set status = 'failed', attempts = coalesce(?, attempts), next_attempt_at = null, lease_until = null, "
                + "error_code = ?, error_message = ? where delivery_id = ? and status in ('requested', 'accepted')", attemptsAfter, errorCode, errorMessage,
                deliveryId) == 1;
    }

    @Override
    public boolean answered(UUID requestId, Instant now) {
        return jdbc.update("update delivery.seller_request set status = 'answered', answered_at = ?, next_attempt_at = null, lease_until = null "
                + "where request_id = ? and status in ('requested', 'accepted')", time(now), requestId) == 1;
    }

    @Override
    public boolean timeout(UUID deliveryId) {
        return jdbc.update("update delivery.seller_request set status = 'timeout', next_attempt_at = null, lease_until = null where delivery_id = ? "
                + "and status in ('requested', 'accepted')", deliveryId) == 1;
    }

    @Override
    public Optional<SellerRequestState> find(UUID requestId) {
        return jdbc.query("select r.delivery_id, r.request_id, d.order_id, r.seller_id, d.quantity, r.status, r.created_at, r.deadline_at, r.lease_until, r.error_code "
                + "from delivery.seller_request r join delivery.delivery d on d.id = r.delivery_id where r.request_id = ?", STATE, requestId).stream().findFirst();
    }

    @Override
    public boolean lease(UUID requestId, Instant now, Instant leaseUntil) {
        return jdbc.update("update delivery.seller_request set lease_until = ? where request_id = ? and status in ('requested', 'accepted') "
                + "and deadline_at > ? and (lease_until is null or lease_until <= ?)", time(leaseUntil), requestId, time(now), time(now)) == 1;
    }

    @Override
    public void release(UUID requestId) {
        jdbc.update("update delivery.seller_request set lease_until = null where request_id = ? and status in ('requested', 'accepted')", requestId);
    }

    @Override
    public List<ExpiredRequest> lockExpired(Instant now, int limit) {
        return jdbc.query("select r.delivery_id, r.request_id, d.order_id, r.seller_id, r.created_at from delivery.seller_request r "
                + "join delivery.delivery d on d.id = r.delivery_id where r.status in ('requested', 'accepted') and r.deadline_at <= ? "
                + "and (r.lease_until is null or r.lease_until <= ?) order by r.deadline_at, r.delivery_id limit ? for update of r skip locked",
                EXPIRED, time(now), time(now), limit);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime time(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
