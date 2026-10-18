package dgm.kit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.jdbc.RowClaim;
import dgm.kit.testing.Concurrent;
import dgm.kit.time.Clocks;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Помощник аренды {@link RowClaim} на настоящем PostgreSQL стенда (правило 15, ADR-007): аренда берёт только наступившие строки в порядке
 * срока и не больше предела, арендованная строка возвращается в очередь после срока аренды (упавший исполнитель), несколько
 * исполнителей разбирают очередь без повторов, блокировка в транзакции пропускает строки, занятые другой транзакцией. Очередью служит
 * таблица {@code idempotency_key} базы заказов: столбец {@code key} это идентификатор, {@code created_at} срок. Строки теста помечены
 * собственной областью {@code scope}, чужие записи стенда не мешают.
 */
@Tag("integration")
class LeaseIT {

    private static final RowMapper<UUID> KEY = (rs, row) -> rs.getObject("key", UUID.class);

    private static final Clock CLOCK = Clocks.system();
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private final List<String> scopes = new ArrayList<>();

    @BeforeAll
    static void connect() {
        DataSource db = new BoundedDataSource(Env.orderDb(), 8);
        jdbc = new JdbcTemplate(db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db));
    }

    @AfterEach
    void cleanUp() {
        for (String scope : scopes) {
            jdbc.update("delete from idempotency_key where scope = ?", scope);
        }
    }

    private String scope(String name) {
        String scope = Env.subject(name);
        scopes.add(scope);
        return scope;
    }

    private static RowClaim queue(String scope) {
        return RowClaim.on("public.idempotency_key").idColumn("key").where("scope = '" + scope + "' and state = 'in_progress'")
                .dueColumn("created_at").returning("key, endpoint").build();
    }

    /** Добавляет строки со сроками {@code first}, {@code first + step}, …; возвращает ключи в порядке срока. */
    private static List<UUID> seed(String scope, int count, Instant first, Duration step) {
        List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID key = UUID.randomUUID();
            jdbc.update("insert into idempotency_key (scope, key, endpoint, request_hash, state, created_at) values (?, ?, 'it', ?, 'in_progress', ?)",
                    scope, key, new byte[32], Timestamp.from(first.plus(step.multipliedBy(i))));
            keys.add(key);
        }
        return keys;
    }

    @Test
    void claimTakesOnlyDueRowsInDueOrderAndRespectsTheLimit() {
        String scope = scope("order");
        Instant now = CLOCK.instant();
        List<UUID> due = seed(scope, 5, now.minusSeconds(100), Duration.ofSeconds(10));
        seed(scope, 2, now.plusSeconds(3600), Duration.ofSeconds(1));
        RowClaim queue = queue(scope);

        List<UUID> first = queue.claim(jdbc, now, Duration.ofMinutes(2), 3, KEY);
        assertEquals(new HashSet<>(due.subList(0, 3)), new HashSet<>(first), "сначала самые давние");
        assertEquals(3, first.size());
        List<UUID> second = queue.claim(jdbc, now, Duration.ofMinutes(2), 10, KEY);
        assertEquals(new HashSet<>(due.subList(3, 5)), new HashSet<>(second));
        assertEquals(2, second.size());
        assertEquals(List.of(), queue.claim(jdbc, now, Duration.ofMinutes(2), 10, KEY), "ненаступившие строки не берутся");
    }

    @Test
    void leasedRowsComeBackAfterTheLeaseExpires() {
        String scope = scope("lease");
        Instant now = CLOCK.instant();
        List<UUID> keys = seed(scope, 3, now.minusSeconds(30), Duration.ofSeconds(1));
        RowClaim queue = queue(scope);

        assertEquals(new HashSet<>(keys), new HashSet<>(queue.claim(jdbc, now, Duration.ofMinutes(2), 10, KEY)));
        assertEquals(List.of(), queue.claim(jdbc, now.plusSeconds(60), Duration.ofMinutes(2), 10, KEY), "аренда ещё действует");
        // Исполнитель упал и не вернул строки: после срока аренды они снова наступили
        assertEquals(new HashSet<>(keys), new HashSet<>(queue.claim(jdbc, now.plusSeconds(121), Duration.ofMinutes(2), 10, KEY)));
    }

    @Test
    void alsoSetAndReturnedColumnsAreApplied() {
        String scope = scope("columns");
        Instant now = CLOCK.instant();
        seed(scope, 2, now.minusSeconds(30), Duration.ofSeconds(1));
        RowClaim queue = RowClaim.on("public.idempotency_key").idColumn("key").where("scope = '" + scope + "' and state = 'in_progress'")
                .dueColumn("created_at").alsoSet("endpoint = 'claimed'").returning("key, endpoint").build();
        List<String> endpoints = queue.claim(jdbc, now, Duration.ofMinutes(1), 10, (rs, row) -> rs.getString("endpoint"));
        assertEquals(List.of("claimed", "claimed"), endpoints);
        assertEquals(2, jdbc.queryForObject("select count(*) from idempotency_key where scope = ? and endpoint = 'claimed'", Integer.class, scope));
    }

    @Test
    void lockWithoutTransactionIsRefused() {
        String scope = scope("no-tx");
        assertThrows(IllegalStateException.class, () -> queue(scope).lock(jdbc, CLOCK.instant(), 1, KEY));
    }

    @Tag("concurrency")
    @Test
    void lockSkipsRowsHeldByAnotherTransactionInsteadOfWaiting() throws Exception {
        String scope = scope("skip-locked");
        Instant now = CLOCK.instant();
        List<UUID> keys = seed(scope, 6, now.minusSeconds(60), Duration.ofSeconds(1));
        RowClaim queue = queue(scope);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Set<UUID> heldByFirst = new HashSet<>();
            Set<UUID> takenBySecond = new HashSet<>();
            tx.executeWithoutResult(status -> {
                heldByFirst.addAll(queue.lock(jdbc, now, 3, KEY));
                // Вторая транзакция идёт, пока первая держит свои строки: она обязана получить другие, а не ждать конца первой
                Future<List<UUID>> second = other.submit(() -> tx.execute(s -> queue.lock(jdbc, now, 3, KEY)));
                try {
                    takenBySecond.addAll(second.get(15, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError("вторая транзакция ждала блокировку вместо пропуска строк", e);
                }
            });
            assertEquals(3, heldByFirst.size());
            assertEquals(3, takenBySecond.size());
            Set<UUID> intersection = new HashSet<>(heldByFirst);
            intersection.retainAll(takenBySecond);
            assertTrue(intersection.isEmpty(), "общих строк быть не должно: " + intersection);
            Set<UUID> union = new HashSet<>(heldByFirst);
            union.addAll(takenBySecond);
            assertEquals(new HashSet<>(keys), union);
        } finally {
            other.shutdownNow();
        }
    }

    @Tag("concurrency")
    @Test
    void severalConsumersDrainTheQueueWithoutTakingARowTwice() {
        String scope = scope("drain");
        Instant now = CLOCK.instant();
        List<UUID> keys = seed(scope, 120, now.minusSeconds(300), Duration.ofMillis(100));
        RowClaim queue = queue(scope);

        List<List<UUID>> perConsumer = Concurrent.race(4, consumer -> {
            List<UUID> mine = new ArrayList<>();
            while (true) {
                List<UUID> batch = queue.claim(jdbc, CLOCK.instant(), Duration.ofMinutes(5), 7, KEY);
                if (batch.isEmpty()) {
                    return mine;
                }
                mine.addAll(batch);
            }
        });

        List<UUID> all = new ArrayList<>();
        perConsumer.forEach(all::addAll);
        assertEquals(keys.size(), all.size(), "всего взято ровно по числу строк: повторов нет");
        assertEquals(new HashSet<>(keys), new HashSet<>(all), "каждая строка взята");
    }
}
