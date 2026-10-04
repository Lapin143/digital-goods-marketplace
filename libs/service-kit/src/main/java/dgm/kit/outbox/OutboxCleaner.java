package dgm.kit.outbox;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ежедневная очистка Outbox (ADR-005): удаляются отправленные строки старше 3 суток пачками по 1000. Припаркованные строки не
 * удаляются, пока их не разобрали, неотправленные тоже.
 */
public final class OutboxCleaner {

    public static final Duration RETENTION = Duration.ofDays(3);
    public static final int BATCH = 1000;

    private static final String DELETE = "delete from outbox where id in (select id from outbox where published_at is not null "
            + "and published_at < now() - make_interval(secs => ?) order by id limit ?)";

    private final JdbcTemplate jdbc;
    private final Duration retention;

    public OutboxCleaner(JdbcTemplate jdbc) {
        this(jdbc, RETENTION);
    }

    public OutboxCleaner(JdbcTemplate jdbc, Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /** Удаляет все подлежащие очистке строки пачками, возвращает число удалённых. */
    public int clean() {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(DELETE, (double) retention.toSeconds(), BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }
}
