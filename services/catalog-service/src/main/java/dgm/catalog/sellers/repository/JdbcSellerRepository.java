package dgm.catalog.sellers.repository;

import dgm.catalog.sellers.domain.DocumentType;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.domain.SellerType;
import dgm.kit.boot.ModuleDatabases;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Профили и документы в PostgreSQL под ролью {@code app_seller_onboarding}. Свои таблицы читаются по индексам из DDL. */
@Component
public final class JdbcSellerRepository implements SellerRepository {

    /** Имя модуля базы в {@code dgm.db.modules} (application.yml): своя роль, свой пул, своя транзакция. */
    public static final String MODULE = "seller_onboarding";

    private static final String PROFILE_COLUMNS = "id, user_id, seller_type, name, payout_recipient_name, payout_bank_name, payout_bik, "
            + "payout_account_number, assortment_description, decision_reason, last_rejected_at, submitted_at, review_deadline_at, "
            + "overdue_marked_at, status, version, created_at, updated_at";
    private static final String DOCUMENT_COLUMNS = "d.id, d.seller_id, d.object_key, d.file_name, d.content_type, d.size_bytes, d.uploaded_at";

    private static final RowMapper<SellerProfile> PROFILE = JdbcSellerRepository::profile;
    private static final RowMapper<SellerDocument> DOCUMENT = JdbcSellerRepository::document;

    private final JdbcTemplate jdbc;

    public JdbcSellerRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(MODULE);
    }

    @Override
    public Optional<SellerProfile> findByUser(UUID userId) {
        return jdbc.query("select " + PROFILE_COLUMNS + " from seller_onboarding.seller_profile where user_id = ?", PROFILE, userId)
                .stream().findFirst();
    }

    @Override
    public Optional<SellerProfile> lockByUser(UUID userId) {
        return jdbc.query("select " + PROFILE_COLUMNS + " from seller_onboarding.seller_profile where user_id = ? for update", PROFILE, userId)
                .stream().findFirst();
    }

    @Override
    public boolean insert(SellerProfile p) {
        SellerForm f = p.form();
        int rows = jdbc.update("insert into seller_onboarding.seller_profile (id, user_id, seller_type, name, payout_recipient_name, "
                        + "payout_bank_name, payout_bik, payout_account_number, assortment_description, decision_reason, last_rejected_at, "
                        + "submitted_at, review_deadline_at, overdue_marked_at, status, version, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (user_id) do nothing",
                p.id(), p.userId(), f.sellerType() == null ? null : f.sellerType().code(), f.name(), f.payoutRecipientName(),
                f.payoutBankName(), f.payoutBik(), f.payoutAccountNumber(), f.assortmentDescription(), p.decisionReason(),
                time(p.lastRejectedAt()), time(p.submittedAt()), time(p.reviewDeadlineAt()), time(p.overdueMarkedAt()), p.status().code(),
                p.version(), time(p.createdAt()), time(p.updatedAt()));
        return rows == 1;
    }

    @Override
    public boolean update(SellerProfile p, int expectedVersion) {
        SellerForm f = p.form();
        int rows = jdbc.update("update seller_onboarding.seller_profile set seller_type = ?, name = ?, payout_recipient_name = ?, "
                        + "payout_bank_name = ?, payout_bik = ?, payout_account_number = ?, assortment_description = ?, decision_reason = ?, "
                        + "last_rejected_at = ?, submitted_at = ?, review_deadline_at = ?, overdue_marked_at = ?, status = ?, version = ?, "
                        + "updated_at = ? where id = ? and version = ?",
                f.sellerType() == null ? null : f.sellerType().code(), f.name(), f.payoutRecipientName(), f.payoutBankName(), f.payoutBik(),
                f.payoutAccountNumber(), f.assortmentDescription(), p.decisionReason(), time(p.lastRejectedAt()), time(p.submittedAt()),
                time(p.reviewDeadlineAt()), time(p.overdueMarkedAt()), p.status().code(), p.version(), time(p.updatedAt()), p.id(),
                expectedVersion);
        return rows == 1;
    }

    @Override
    public List<SellerDocument> documents(UUID sellerId) {
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from seller_onboarding.seller_document d where d.seller_id = ? "
                + "order by d.uploaded_at, d.id", DOCUMENT, sellerId);
    }

    @Override
    public int countDocuments(UUID sellerId) {
        Integer count = jdbc.queryForObject("select count(*) from seller_onboarding.seller_document where seller_id = ?", Integer.class, sellerId);
        return count == null ? 0 : count;
    }

    @Override
    public void insertDocument(SellerDocument d) {
        jdbc.update("insert into seller_onboarding.seller_document (id, seller_id, object_key, file_name, content_type, size_bytes, uploaded_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                d.id(), d.sellerId(), d.objectKey(), d.fileName(), d.type().contentType(), d.sizeBytes(), time(d.uploadedAt()));
    }

    @Override
    public Optional<SellerDocument> findDocument(UUID sellerId, UUID documentId) {
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from seller_onboarding.seller_document d where d.seller_id = ? and d.id = ?",
                DOCUMENT, sellerId, documentId).stream().findFirst();
    }

    @Override
    public boolean deleteDocument(UUID sellerId, UUID documentId) {
        return jdbc.update("delete from seller_onboarding.seller_document where seller_id = ? and id = ?", sellerId, documentId) == 1;
    }

    @Override
    public Optional<SellerDocument> findUserDocument(UUID userId, UUID documentId) {
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from seller_onboarding.seller_document d "
                + "join seller_onboarding.seller_profile p on p.id = d.seller_id where p.user_id = ? and d.id = ?", DOCUMENT, userId, documentId)
                .stream().findFirst();
    }

    @Override
    public List<UUID> markOverdue(Instant now) {
        // Один оператор: выбор и пометка в одном запросе, поэтому два экземпляра не пометят одну заявку дважды (индекс ix_seller_profile_overdue)
        // updated_at и version не меняются: пометка служебная, заявитель её не видит и ETag анкеты от неё не зависит
        return jdbc.query("update seller_onboarding.seller_profile set overdue_marked_at = ? "
                + "where status = 'on_review' and overdue_marked_at is null and review_deadline_at <= ? returning id",
                (rs, row) -> rs.getObject("id", UUID.class), time(now), time(now));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static SellerProfile profile(ResultSet rs, int row) throws SQLException {
        String type = rs.getString("seller_type");
        SellerForm form = new SellerForm(type == null ? null : SellerType.fromCode(type).orElseThrow(), rs.getString("name"),
                rs.getString("payout_recipient_name"), rs.getString("payout_bank_name"), rs.getString("payout_bik"),
                rs.getString("payout_account_number"), rs.getString("assortment_description"));
        return new SellerProfile(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), SellerStatus.fromCode(rs.getString("status")),
                form, rs.getString("decision_reason"), instant(rs, "last_rejected_at"), instant(rs, "submitted_at"),
                instant(rs, "review_deadline_at"), instant(rs, "overdue_marked_at"), rs.getInt("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static SellerDocument document(ResultSet rs, int row) throws SQLException {
        return new SellerDocument(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getString("object_key"),
                rs.getString("file_name"), DocumentType.fromContentType(rs.getString("content_type")).orElseThrow(), rs.getLong("size_bytes"),
                instant(rs, "uploaded_at"));
    }
}
