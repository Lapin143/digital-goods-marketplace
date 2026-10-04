package dgm.kit.events;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link ProcessedEvents} на таблице {@code processed_event}: {@code insert ... on conflict do nothing} в транзакции обработчика. */
public final class JdbcProcessedEvents implements ProcessedEvents {

    /** Срок хранения обработанных событий: 14 суток (ADR-006). */
    public static final Duration RETENTION = Duration.ofDays(14);

    private final JdbcTemplate jdbc;

    public JdbcProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.update("insert into processed_event (consumer, event_id) values (?, ?) on conflict do nothing", consumer, eventId) == 1;
    }

    /** Удаляет записи старше срока хранения, возвращает число удалённых. Вызывается раз в сутки. */
    public int clean(Duration retention) {
        return jdbc.update("delete from processed_event where processed_at < now() - make_interval(secs => ?)", (double) retention.toSeconds());
    }

    public int clean() {
        return clean(RETENTION);
    }
}
