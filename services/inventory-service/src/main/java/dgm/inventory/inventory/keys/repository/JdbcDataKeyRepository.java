package dgm.inventory.inventory.keys.repository;

import dgm.inventory.inventory.registry.JdbcProductCopyRepository;
import dgm.kit.boot.ModuleDatabases;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Ключи данных в PostgreSQL под ролью {@code app_inventory}. */
@Component
public final class JdbcDataKeyRepository implements DataKeyRepository {

    /** Произвольный постоянный номер блокировки создания первого DEK. */
    private static final long LOCK_ID = 0x44474D4B455931L;

    private static final RowMapper<Stored> STORED = (rs, row) -> new Stored(rs.getInt("id"), rs.getBytes("wrapped_key"));

    private final JdbcTemplate jdbc;

    public JdbcDataKeyRepository(ModuleDatabases databases) {
        this.jdbc = databases.jdbc(JdbcProductCopyRepository.MODULE);
    }

    @Override
    public Optional<Stored> active() {
        return jdbc.query("select id, wrapped_key from inventory.data_key where status = 'active'", STORED).stream().findFirst();
    }

    @Override
    public Optional<Stored> byId(int id) {
        return jdbc.query("select id, wrapped_key from inventory.data_key where id = ?", STORED, id).stream().findFirst();
    }

    @Override
    public void lockCreation() {
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> {
        }, LOCK_ID);
    }

    @Override
    public Stored insertActive(byte[] wrapped, int kekVersion) {
        return jdbc.queryForObject("insert into inventory.data_key (wrapped_key, kek_version, status) values (?, ?, 'active') "
                + "returning id, wrapped_key", STORED, wrapped, kekVersion);
    }
}
