package dgm.kit.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.idempotency.IdempotencyGuard;
import dgm.kit.idempotency.IdempotencyRequest;
import dgm.kit.idempotency.JdbcIdempotencyStore;
import dgm.kit.idempotency.StoredResponse;
import dgm.kit.json.Json;
import dgm.kit.outbox.OutboxWriter;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Clocks;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Охранник идемпотентности на настоящем PostgreSQL стенда (ADR-006): двадцать одновременных повторов дают одно действие и двадцать
 * одинаковых ответов, откат действия не оставляет ни события, ни ключа, брошенный ключ подхватывается, очистка убирает старое.
 */
@Tag("integration")
class IdempotencyGuardIT {

    private static final String ENDPOINT = "POST /api/v1/orders";
    private static final String BODY = "{\"items\":[{\"productId\":\"p-1\",\"quantity\":1}]}";

    private static JdbcTemplate jdbc;
    private static JdbcIdempotencyStore store;
    private static OutboxWriter writer;
    private static TransactionTemplate tx;
    private static IdempotencyGuard guard;

    private final List<String> scopes = new ArrayList<>();

    @BeforeAll
    static void connect() {
        DataSource db = new BoundedDataSource(Env.orderDb(), 4);
        jdbc = new JdbcTemplate(db);
        tx = new TransactionTemplate(new DataSourceTransactionManager(db));
        store = new JdbcIdempotencyStore(jdbc);
        writer = new OutboxWriter(jdbc, Clocks.system());
        guard = new IdempotencyGuard(store, tx, new IdempotencyGuard.Settings(Duration.ofSeconds(10), Duration.ofMillis(20), Duration.ofSeconds(60)),
                Clocks.system());
    }

    @AfterEach
    void cleanUp() {
        for (String scope : scopes) {
            jdbc.update("delete from idempotency_key where scope = ?", scope);
            jdbc.update("delete from outbox where aggregate_id = ?", scope);
        }
    }

    /** Область теста: у каждого теста своя, поэтому чужие записи стенда и соседние тесты не мешают. */
    private String scope(String name) {
        String scope = Env.subject(name);
        scopes.add(scope);
        return scope;
    }

    private static IdempotencyRequest request(String scope, UUID key, String body) {
        return new IdempotencyRequest(scope, key, ENDPOINT, IdempotencyRequest.sha256(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Действие бизнес-кода: пишет событие в Outbox (изменение данных), немного работает и отвечает 201. */
    private static Supplier<StoredResponse> createOrder(String scope, AtomicInteger runs, long workMillis) {
        return () -> {
            int n = runs.incrementAndGet();
            writer.write("order.events", "Order", scope, "order.created", 1, Map.of("run", n));
            try {
                Thread.sleep(workMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new StoredResponse(201, "{\"orderId\":\"" + UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)) + "\"}",
                    Map.of("Location", "/api/v1/orders/1", "ETag", "\"1\""));
        };
    }

    private static int outboxRows(String scope) {
        return jdbc.queryForObject("select count(*) from outbox where aggregate_id = ?", Integer.class, scope);
    }

    private static int keyRows(String scope, String state) {
        return jdbc.queryForObject("select count(*) from idempotency_key where scope = ? and state = ?", Integer.class, scope, state);
    }

    @Test
    void twentyParallelRepeatsRunTheActionOnceAndGetTheSameAnswer() throws Exception {
        String scope = scope("parallel");
        UUID key = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        int callers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch ready = new CountDownLatch(callers);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<IdempotencyGuard.Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return guard.execute(request(scope, key, BODY), createOrder(scope, runs, 300));
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();

            List<IdempotencyGuard.Outcome> outcomes = new ArrayList<>();
            for (Future<IdempotencyGuard.Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }

            assertEquals(1, runs.get(), "действие выполнено один раз");
            assertEquals(1, outcomes.stream().filter(o -> !o.replayed()).count());
            assertEquals(callers - 1, outcomes.stream().filter(IdempotencyGuard.Outcome::replayed).count());
            Set<Object> bodies = new HashSet<>();
            for (IdempotencyGuard.Outcome outcome : outcomes) {
                assertEquals(201, outcome.response().status());
                assertEquals("/api/v1/orders/1", outcome.response().headers().get("Location"));
                assertEquals("\"1\"", outcome.response().headers().get("ETag"));
                bodies.add(Json.parseObject(outcome.response().body()));
            }
            assertEquals(1, bodies.size(), "все ответы одинаковые");
            assertEquals(1, outboxRows(scope), "событие записано один раз");
            assertEquals(1, keyRows(scope, "completed"));
            assertEquals(0, keyRows(scope, "in_progress"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repeatAfterCompletionReturnsTheStoredAnswerWithoutRunning() {
        String scope = scope("repeat");
        UUID key = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        IdempotencyGuard.Outcome first = guard.execute(request(scope, key, BODY), createOrder(scope, runs, 0));
        IdempotencyGuard.Outcome second = guard.execute(request(scope, key, BODY), createOrder(scope, runs, 0));
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(1, runs.get());
        assertEquals(Json.parseObject(first.response().body()), Json.parseObject(second.response().body()));
        assertEquals(first.response().headers(), second.response().headers());
    }

    @Test
    void sameKeyWithAnotherBodyIsRefused() {
        String scope = scope("reuse");
        UUID key = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        guard.execute(request(scope, key, BODY), createOrder(scope, runs, 0));
        ProblemException e = assertThrows(ProblemException.class,
                () -> guard.execute(request(scope, key, "{\"items\":[]}"), createOrder(scope, runs, 0)));
        assertEquals(ProblemType.IDEMPOTENCY_KEY_REUSE, e.type());
        assertEquals(1, runs.get());
        assertEquals(1, outboxRows(scope));
    }

    @Test
    void failedActionLeavesNoEventAndNoKeyAndCanBeRepeated() {
        String scope = scope("rollback");
        UUID key = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> guard.execute(request(scope, key, BODY), () -> {
            writer.write("order.events", "Order", scope, "order.created", 1, Map.of("run", 1));
            throw new IllegalStateException("сбой после записи события");
        }));
        assertEquals(0, outboxRows(scope), "событие откатилось вместе с действием");
        assertEquals(0, keyRows(scope, "in_progress") + keyRows(scope, "completed"), "ключ освобождён");

        IdempotencyGuard.Outcome repeat = guard.execute(request(scope, key, BODY), createOrder(scope, runs, 0));
        assertFalse(repeat.replayed());
        assertEquals(1, runs.get());
        assertEquals(1, outboxRows(scope));
    }

    @Test
    void errorAnswerIsNotRememberedAndItsChangesAreRolledBack() {
        String scope = scope("error");
        UUID key = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        IdempotencyGuard.Outcome refused = guard.execute(request(scope, key, BODY), () -> {
            writer.write("order.events", "Order", scope, "order.created", 1, Map.of("run", 1));
            return new StoredResponse(422, "{\"code\":\"validation-failed\"}", Map.of());
        });
        assertEquals(422, refused.response().status());
        assertEquals(0, outboxRows(scope));
        assertEquals(0, keyRows(scope, "in_progress") + keyRows(scope, "completed"));

        IdempotencyGuard.Outcome repeat = guard.execute(request(scope, key, BODY), createOrder(scope, runs, 0));
        assertEquals(201, repeat.response().status());
        assertFalse(repeat.replayed());
    }

    @Test
    void differentScopesDoNotShareKeys() {
        String a = scope("scope-a");
        String b = scope("scope-b");
        UUID key = UUID.randomUUID();
        AtomicInteger runsA = new AtomicInteger();
        AtomicInteger runsB = new AtomicInteger();
        guard.execute(request(a, key, BODY), createOrder(a, runsA, 0));
        IdempotencyGuard.Outcome second = guard.execute(request(b, key, BODY), createOrder(b, runsB, 0));
        assertFalse(second.replayed());
        assertEquals(1, runsA.get());
        assertEquals(1, runsB.get());
    }

    @Test
    void abandonedKeyIsTakenOverAndAFreshOneIsNot() {
        String scope = scope("abandoned");
        UUID key = UUID.randomUUID();
        IdempotencyRequest request = request(scope, key, BODY);
        assertTrue(store.claim(request), "ключ занят процессом, который затем «упал»");

        IdempotencyGuard shortWait = new IdempotencyGuard(store, tx,
                new IdempotencyGuard.Settings(Duration.ofMillis(200), Duration.ofMillis(20), Duration.ofSeconds(60)), Clocks.system());
        ProblemException busy = assertThrows(ProblemException.class,
                () -> shortWait.execute(request, createOrder(scope, new AtomicInteger(), 0)));
        assertEquals(ProblemType.REQUEST_IN_PROGRESS, busy.type());

        jdbc.update("update idempotency_key set created_at = now() - interval '2 minutes' where scope = ? and key = ?", scope, key);
        AtomicInteger runs = new AtomicInteger();
        IdempotencyGuard.Outcome outcome = shortWait.execute(request, createOrder(scope, runs, 0));
        assertFalse(outcome.replayed());
        assertEquals(1, runs.get());
        assertEquals(1, keyRows(scope, "completed"));
    }

    @Test
    void purgeRemovesKeysOlderThanADayAndKeepsFreshOnes() {
        String scope = scope("purge");
        UUID oldKey = UUID.randomUUID();
        UUID freshKey = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        guard.execute(request(scope, oldKey, BODY), createOrder(scope, runs, 0));
        guard.execute(request(scope, freshKey, BODY), createOrder(scope, runs, 0));
        jdbc.update("update idempotency_key set created_at = now() - interval '25 hours' where scope = ? and key = ?", scope, oldKey);

        int removed = store.purge(IdempotencyGuard.RETENTION.toSeconds());

        assertTrue(removed >= 1);
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_key where scope = ? and key = ?", Integer.class, scope, oldKey));
        assertEquals(1, jdbc.queryForObject("select count(*) from idempotency_key where scope = ? and key = ?", Integer.class, scope, freshKey));
        assertNotNull(store.find(scope, freshKey).orElse(null));
    }
}
