package dgm.catalog.sellers.apikey.repository;

import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.catalog.sellers.apikey.domain.ApiKeyPage;
import dgm.catalog.sellers.apikey.domain.ApiKeyStatus;
import dgm.catalog.sellers.apikey.domain.KeyCheck;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.repository.JdbcSellerRepository;
import dgm.kit.boot.ModuleDatabases;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** API-ключи в PostgreSQL под ролью {@code app_seller_onboarding}. Столбец {@code key_hash} читается только поиском по хешу. */
@Component
public final class JdbcApiKeyRepository implements ApiKeyRepository {

    private static final String COLUMNS = "id, seller_id, name, fingerprint, status, created_at, revoked_at";
    private static final RowMapper<ApiKey> KEY = JdbcApiKeyRepository::key;

    private final JdbcTemplate jdbc;

    public JdbcApiKeyRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcSellerRepository.MODULE);
    }

    @Override
    public int countActive(UUID sellerId) {
        Integer count = jdbc.queryForObject("select count(*) from seller_onboarding.api_key where seller_id = ? and status = 'active'",
                Integer.class, sellerId);
        return count == null ? 0 : count;
    }

    @Override
    public void insert(ApiKey key, byte[] keyHash) {
        jdbc.update("insert into seller_onboarding.api_key (id, seller_id, name, key_hash, fingerprint, status, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                key.id(), key.sellerId(), key.name(), keyHash, key.fingerprint(), key.status().code(), time(key.createdAt()));
    }

    @Override
    public Optional<ApiKey> find(UUID sellerId, UUID apiKeyId) {
        return jdbc.query("select " + COLUMNS + " from seller_onboarding.api_key where seller_id = ? and id = ?", KEY, sellerId, apiKeyId)
                .stream().findFirst();
    }

    @Override
    public Optional<ApiKey> lock(UUID sellerId, UUID apiKeyId) {
        return jdbc.query("select " + COLUMNS + " from seller_onboarding.api_key where seller_id = ? and id = ? for update", KEY, sellerId,
                apiKeyId).stream().findFirst();
    }

    @Override
    public boolean markRevoked(ApiKey revoked) {
        // Условие status = 'active' держит и сама база: триггер trg_api_key_status_transition не пускает переход из «отозван»
        return jdbc.update("update seller_onboarding.api_key set status = 'revoked', revoked_at = ? where id = ? and seller_id = ? and status = 'active'",
                time(revoked.revokedAt()), revoked.id(), revoked.sellerId()) == 1;
    }

    @Override
    public List<ApiKey> list(UUID sellerId, Optional<ApiKeyStatus> status, Optional<ApiKeyPage.After> after, int limit) {
        StringBuilder sql = new StringBuilder("select " + COLUMNS + " from seller_onboarding.api_key where seller_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(sellerId);
        status.ifPresent(s -> {
            sql.append(" and status = ?");
            args.add(s.code());
        });
        after.ifPresent(a -> {
            sql.append(" and (created_at, id) < (?, ?)");
            args.add(time(a.createdAt()));
            args.add(a.id());
        });
        sql.append(" order by created_at desc, id desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), KEY, args.toArray());
    }

    @Override
    public Optional<KeyCheck> findByHash(byte[] keyHash) {
        return jdbc.query("select k.id, k.seller_id, k.status as key_status, p.status as seller_status from seller_onboarding.api_key k "
                        + "join seller_onboarding.seller_profile p on p.id = k.seller_id where k.key_hash = ?",
                (rs, row) -> new KeyCheck(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class),
                        ApiKeyStatus.fromCode(rs.getString("key_status")).orElseThrow(), SellerStatus.fromCode(rs.getString("seller_status"))),
                (Object) keyHash).stream().findFirst();
    }

    private static OffsetDateTime time(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static ApiKey key(ResultSet rs, int row) throws SQLException {
        return new ApiKey(rs.getObject("id", UUID.class), rs.getObject("seller_id", UUID.class), rs.getString("name"), rs.getString("fingerprint"),
                ApiKeyStatus.fromCode(rs.getString("status")).orElseThrow(), instant(rs, "created_at"), instant(rs, "revoked_at"));
    }
}
