package dgm.catalog.sellers.integration.repository;

import dgm.catalog.sellers.integration.domain.IntegrationStatus;
import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.catalog.sellers.repository.JdbcSellerRepository;
import dgm.kit.boot.ModuleDatabases;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Подключения систем продавцов в PostgreSQL под ролью {@code app_seller_onboarding}. */
@Component
public final class JdbcIntegrationRepository implements IntegrationRepository {

    private static final String COLUMNS = "seller_id, endpoint_url, secret_version, status, version, created_at, updated_at";
    private static final RowMapper<SellerIntegration> ROW = JdbcIntegrationRepository::row;

    private final JdbcTemplate jdbc;

    public JdbcIntegrationRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcSellerRepository.MODULE);
    }

    @Override
    public Optional<SellerIntegration> find(UUID sellerId) {
        return jdbc.query("select " + COLUMNS + " from seller_onboarding.seller_integration where seller_id = ?", ROW, sellerId).stream().findFirst();
    }

    @Override
    public void insert(SellerIntegration integration) {
        jdbc.update("insert into seller_onboarding.seller_integration (seller_id, endpoint_url, secret_version, status, version, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?)",
                integration.sellerId(), integration.endpointUrl(), integration.secretVersion(), integration.status().code(), integration.version(),
                time(integration.createdAt()), time(integration.updatedAt()));
    }

    @Override
    public SellerIntegration update(UUID sellerId, String endpointUrl, IntegrationStatus status) {
        return one(jdbc.query("update seller_onboarding.seller_integration set endpoint_url = ?, status = ? where seller_id = ? returning " + COLUMNS,
                ROW, endpointUrl, status.code(), sellerId), sellerId);
    }

    @Override
    public SellerIntegration rotateSecret(UUID sellerId) {
        // Версию секрета держит и триггер базы: она не уменьшается (ck_seller_integration_secret_version_down)
        return one(jdbc.query("update seller_onboarding.seller_integration set secret_version = secret_version + 1 where seller_id = ? returning "
                + COLUMNS, ROW, sellerId), sellerId);
    }

    private static SellerIntegration one(java.util.List<SellerIntegration> rows, UUID sellerId) {
        if (rows.size() != 1) {
            // Строка заблокирована вместе с профилем продавца с начала транзакции, поэтому исчезнуть она не могла; проверка защищает от ошибки в коде
            throw new IllegalStateException("Подключение продавца " + sellerId + " изменилось под блокировкой");
        }
        return rows.get(0);
    }

    private static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static SellerIntegration row(ResultSet rs, int row) throws SQLException {
        return new SellerIntegration(rs.getObject("seller_id", UUID.class), rs.getString("endpoint_url"), rs.getInt("secret_version"),
                IntegrationStatus.fromCode(rs.getString("status")).orElseThrow(), rs.getLong("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
