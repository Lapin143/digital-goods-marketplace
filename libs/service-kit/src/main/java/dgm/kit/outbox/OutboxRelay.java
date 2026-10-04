package dgm.kit.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Публикатор Outbox (компонент {@code outbox-relay}, ADR-005): читает неотправленные строки по возрастанию {@code id}, отправляет их
 * в Kafka и помечает {@code published_at}.
 *
 * <p>Активен один экземпляр сервиса: его определяет консультативная блокировка {@code pg_try_advisory_lock}, которую держит отдельное
 * соединение с базой. Если соединение или экземпляр пропали, блокировка снимается сама и её берёт другой экземпляр. Порядок событий
 * сохраняется: при неудаче пачка прерывается, и следующие строки не обгоняют неотправленную. Сообщение уходит минимум один раз:
 * после сбоя между отправкой и пометкой оно уйдёт снова, дубль убирает потребитель по {@code id}.
 */
public final class OutboxRelay implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String SELECT = "select id, event_id, aggregate_type, aggregate_id, event_type, topic, payload::text, headers::text, "
            + "created_at, attempts from outbox where published_at is null and failed_at is null order by id limit ?";
    private static final String MARK_PUBLISHED = "update outbox set published_at = now() where id = ? and published_at is null";
    private static final String MARK_FAILED = "update outbox set attempts = attempts + 1 where id = ?";
    private static final String PARK = "update outbox set attempts = attempts + 1, failed_at = now() where id = ?";

    private final DataSource dataSource;
    private final EventPublisher publisher;
    private final EnvelopeBuilder envelopes;
    private final OutboxSettings settings;
    private final Clock clock;

    private final AtomicLong lagSeconds = new AtomicLong();
    private final AtomicLong leader = new AtomicLong();
    private final Counter published;
    private final Counter failures;
    private final Counter parked;

    private Connection leaderConnection;
    private Instant blockedUntil = Instant.MIN;
    private int failureStreak;
    private volatile boolean running;
    private Thread thread;

    /**
     * @param source имя сервиса-издателя для атрибута {@code source} конверта
     */
    public OutboxRelay(DataSource dataSource, EventPublisher publisher, String source, OutboxSettings settings, Clock clock,
                       MeterRegistry metrics) {
        this.dataSource = dataSource;
        this.publisher = publisher;
        this.envelopes = new EnvelopeBuilder(source);
        this.settings = settings;
        this.clock = clock;
        // Возраст самой старой неотправленной строки на момент последнего цикла (оповещение при значении больше 60 с)
        metrics.gauge("outbox_lag_seconds", lagSeconds, AtomicLong::doubleValue);
        metrics.gauge("outbox_leader", leader, AtomicLong::doubleValue);
        this.published = metrics.counter("outbox_published_total");
        this.failures = metrics.counter("outbox_publish_failures_total");
        this.parked = metrics.counter("outbox_parked_total");
    }

    /** Запускает цикл публикации в фоновом потоке. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "outbox-relay");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            int sent = 0;
            try {
                sent = runOnce();
            } catch (RuntimeException e) {
                LOG.error("Цикл публикатора Outbox завершился ошибкой", e);
            }
            if (sent == 0) {
                try {
                    Thread.sleep(settings.pollInterval().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Один цикл: проверить лидерство, отправить пачку. Возвращает число отправленных строк. Не лидер или пауза после неудачи: 0. */
    public int runOnce() {
        if (clock.instant().isBefore(blockedUntil)) {
            return 0;
        }
        try {
            if (!ensureLeadership()) {
                return 0;
            }
            return publishBatch(leaderConnection);
        } catch (SQLException e) {
            LOG.warn("Публикатор Outbox потерял соединение с базой: {}", e.getMessage());
            dropConnection();
            blockedUntil = clock.instant().plus(settings.backoff(++failureStreak));
            return 0;
        }
    }

    private boolean ensureLeadership() throws SQLException {
        if (leaderConnection != null) {
            return true;
        }
        Connection c = dataSource.getConnection();
        try {
            c.setAutoCommit(true);
            boolean got;
            try (PreparedStatement ps = c.prepareStatement("select pg_try_advisory_lock(?)")) {
                ps.setLong(1, settings.lockKey());
                try (ResultSet rs = ps.executeQuery()) {
                    got = rs.next() && rs.getBoolean(1);
                }
            }
            if (!got) {
                c.close();
                leader.set(0);
                return false;
            }
            leaderConnection = c;
            leader.set(1);
            LOG.info("Публикатор Outbox стал активным экземпляром");
            return true;
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // соединение уже закрыто сервером
                LOG.debug("Соединение не закрылось: {}", ignored.getMessage());
            }
            throw e;
        }
    }

    private int publishBatch(Connection c) throws SQLException {
        List<OutboxRow> batch = readBatch(c);
        if (batch.isEmpty()) {
            lagSeconds.set(0);
            failureStreak = 0;
            return 0;
        }
        lagSeconds.set(Math.max(0, Duration.between(batch.get(0).createdAt(), clock.instant()).toSeconds()));
        int sent = 0;
        for (OutboxRow row : batch) {
            try {
                publisher.publish(envelopes.build(row));
            } catch (PublishException e) {
                if (handleFailure(c, row, e)) {
                    // Припаркованная строка порядок нарушает осознанно (ADR-005): публикатор идёт дальше
                    continue;
                }
                break;
            }
            execute(c, MARK_PUBLISHED, row.id());
            published.increment();
            failureStreak = 0;
            sent++;
        }
        return sent;
    }

    /** Возвращает true, если строка припаркована и можно идти дальше; false: пачку прерывают и ждут паузу. */
    private boolean handleFailure(Connection c, OutboxRow row, PublishException e) throws SQLException {
        failures.increment();
        int attemptsNow = row.attempts() + 1;
        if (e.unrecoverable() || attemptsNow >= settings.maxAttempts()) {
            execute(c, PARK, row.id());
            parked.increment();
            LOG.error("Событие {} (тип {}, тема {}) припарковано после {} неудач: {}", row.eventId(), row.eventType(), row.topic(),
                    attemptsNow, e.getMessage());
            return true;
        }
        execute(c, MARK_FAILED, row.id());
        Duration pause = settings.backoff(attemptsNow);
        blockedUntil = clock.instant().plus(pause);
        LOG.warn("Событие {} не отправлено (попытка {}), следующая через {} с: {}", row.eventId(), attemptsNow, pause.toSeconds(),
                e.getMessage());
        return false;
    }

    private List<OutboxRow> readBatch(Connection c) throws SQLException {
        List<OutboxRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(SELECT)) {
            ps.setInt(1, settings.batchSize());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new OutboxRow(rs.getLong(1), rs.getObject(2, java.util.UUID.class), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            rs.getObject(9, java.time.OffsetDateTime.class).toInstant(), rs.getInt(10)));
                }
            }
        }
        return rows;
    }

    private static void execute(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Освобождает блокировку и соединение. Блокировку снимают явно: соединение может вернуться в пул, а блокировка действует на сеанс. */
    private void dropConnection() {
        Connection c = leaderConnection;
        leaderConnection = null;
        leader.set(0);
        if (c == null) {
            return;
        }
        try {
            try (PreparedStatement ps = c.prepareStatement("select pg_advisory_unlock(?)")) {
                ps.setLong(1, settings.lockKey());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                }
            }
        } catch (SQLException e) {
            LOG.debug("Блокировка снимется при закрытии сеанса: {}", e.getMessage());
        } finally {
            try {
                c.close();
            } catch (SQLException e) {
                LOG.debug("Соединение не закрылось: {}", e.getMessage());
            }
        }
    }

    /** Останавливает цикл и отдаёт лидерство другому экземпляру. Отправитель событий остаётся открытым: им управляет создатель. */
    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        dropConnection();
    }

    /** Экземпляр сейчас держит блокировку (для тестов и проверки готовности). */
    public boolean isLeader() {
        return leader.get() == 1;
    }
}
