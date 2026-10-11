package dgm.kit.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** Состояния ключа идемпотентности (ADR-006, уровень 1) на хранилище в памяти. */
class IdempotencyGuardTest {

    private static final UUID KEY = UUID.fromString("3f2b8c6e-1a4d-4e0b-9d7a-5c1e2f3a4b5c");
    private static final StoredResponse CREATED = new StoredResponse(201, "{\"id\":\"42\"}", Map.of("Location", "/api/v1/orders/42"));

    private InMemoryIdempotencyStore store;
    private ManualClock clock;
    private AtomicInteger pauses;
    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore();
        clock = new ManualClock(Instant.parse("2026-10-11T10:00:00Z"));
        pauses = new AtomicInteger();
        guard = guard(Duration.ofSeconds(2));
    }

    private IdempotencyGuard guard(Duration wait) {
        IdempotencyGuard.Settings settings = new IdempotencyGuard.Settings(wait, Duration.ofMillis(50), Duration.ofSeconds(60));
        // «Ожидание» двигает часы: так тест доходит до конца срока ожидания без настоящей паузы
        return new IdempotencyGuard(store, TransactionOperations.withoutTransaction(), settings, clock, duration -> {
            pauses.incrementAndGet();
            clock.advance(duration);
        });
    }

    private static IdempotencyRequest request(String scope, String endpoint, String body) {
        return new IdempotencyRequest(scope, KEY, endpoint, IdempotencyRequest.sha256(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void newKeyRunsActionAndStoresResponse() {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyGuard.Outcome outcome = guard.execute(request("u1", "POST /orders", "{}"), () -> {
            runs.incrementAndGet();
            return CREATED;
        });
        assertEquals(1, runs.get());
        assertFalse(outcome.replayed());
        assertSame(CREATED, outcome.response());
        assertEquals(List.of("claim", "complete"), store.calls);
    }

    @Test
    void repeatWithSameBodyReturnsStoredResponseWithoutRunningAction() {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyRequest request = request("u1", "POST /orders", "{\"a\":1}");
        guard.execute(request, () -> {
            runs.incrementAndGet();
            return CREATED;
        });
        IdempotencyGuard.Outcome second = guard.execute(request, () -> {
            runs.incrementAndGet();
            return new StoredResponse(201, "{\"id\":\"other\"}");
        });
        assertEquals(1, runs.get());
        assertTrue(second.replayed());
        assertEquals(CREATED, second.response());
        assertEquals("/api/v1/orders/42", second.response().headers().get("Location"));
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        guard.execute(request("u1", "POST /orders", "{\"a\":1}"), () -> CREATED);
        ProblemException e = assertThrows(ProblemException.class,
                () -> guard.execute(request("u1", "POST /orders", "{\"a\":2}"), () -> CREATED));
        assertEquals(ProblemType.IDEMPOTENCY_KEY_REUSE, e.type());
    }

    @Test
    void sameKeyWithDifferentEndpointIsRejected() {
        guard.execute(request("u1", "POST /orders", "{}"), () -> CREATED);
        ProblemException e = assertThrows(ProblemException.class,
                () -> guard.execute(request("u1", "POST /tickets", "{}"), () -> CREATED));
        assertEquals(ProblemType.IDEMPOTENCY_KEY_REUSE, e.type());
    }

    @Test
    void sameKeyOfAnotherScopeIsIndependent() {
        AtomicInteger runs = new AtomicInteger();
        guard.execute(request("u1", "POST /orders", "{}"), () -> {
            runs.incrementAndGet();
            return CREATED;
        });
        IdempotencyGuard.Outcome other = guard.execute(request("u2", "POST /orders", "{}"), () -> {
            runs.incrementAndGet();
            return CREATED;
        });
        assertEquals(2, runs.get());
        assertFalse(other.replayed());
    }

    @Test
    void failureResponseIsNotRememberedAndKeyIsReleased() {
        AtomicInteger runs = new AtomicInteger();
        IdempotencyRequest request = request("u1", "POST /orders", "{}");
        IdempotencyGuard.Outcome failed = guard.execute(request, () -> {
            runs.incrementAndGet();
            return new StoredResponse(409, "{\"code\":\"insufficient-stock\"}");
        });
        assertEquals(409, failed.response().status());
        assertFalse(failed.replayed());
        assertEquals(List.of("claim", "release"), store.calls);
        assertEquals(0, store.size());

        IdempotencyGuard.Outcome retry = guard.execute(request, () -> {
            runs.incrementAndGet();
            return CREATED;
        });
        assertEquals(2, runs.get());
        assertEquals(201, retry.response().status());
    }

    @Test
    void exceptionInActionReleasesKeyAndPropagates() {
        IdempotencyRequest request = request("u1", "POST /orders", "{}");
        IllegalArgumentException boom = new IllegalArgumentException("boom");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> guard.execute(request, () -> {
            throw boom;
        }));
        assertSame(boom, thrown);
        assertEquals(0, store.size());
        assertEquals(201, guard.execute(request, () -> CREATED).response().status());
    }

    @Test
    void lostKeyDuringCompletionReleasesAndFails() {
        store.completeFailure = new IllegalStateException("ключ потерян");
        IdempotencyRequest request = request("u1", "POST /orders", "{}");
        assertThrows(IllegalStateException.class, () -> guard.execute(request, () -> CREATED));
        assertEquals(List.of("claim", "complete", "release"), store.calls);
    }

    @Test
    void emptyResultOfActionIsProgrammingError() {
        assertThrows(IllegalStateException.class, () -> guard.execute(request("u1", "POST /orders", "{}"), () -> null));
        assertEquals(0, store.size());
    }

    @Test
    void unfinishedFirstRequestGivesInProgressAfterWaiting() {
        IdempotencyRequest request = request("u1", "POST /orders", "{}");
        store.claim(request);
        ProblemException e = assertThrows(ProblemException.class, () -> guard.execute(request, () -> CREATED));
        assertEquals(ProblemType.REQUEST_IN_PROGRESS, e.type());
        assertEquals("1", e.headers().get("Retry-After"));
        // Ожидание шло по 50 мс до срока 2 с
        assertTrue(pauses.get() >= 39 && pauses.get() <= 41, "пауз: " + pauses.get());
    }

    @Test
    void waitingRepeatGetsTheResponseOfTheFirstRequest() {
        IdempotencyRequest request = request("u1", "POST /orders", "{}");
        store.claim(request);
        // Первый запрос завершается, пока повтор ждёт: на третьей проверке ответ уже сохранён
        AtomicInteger finds = new AtomicInteger();
        store.onFind = () -> {
            if (finds.incrementAndGet() == 3) {
                store.finishExternally("u1", KEY, CREATED);
            }
        };
        AtomicInteger runs = new AtomicInteger();
        IdempotencyGuard.Outcome outcome = guard.execute(request, () -> {
            runs.incrementAndGet();
            return CREATED;
        });
        assertEquals(0, runs.get());
        assertTrue(outcome.replayed());
        assertEquals(CREATED, outcome.response());
        assertEquals(2, pauses.get());
    }

    @Test
    void abandonedRequestIsTakenOver() {
        IdempotencyRequest request = request("u1", "POST /orders", "{}");
        store.claim(request);
        store.abandoned = true;
        AtomicInteger runs = new AtomicInteger();
        IdempotencyGuard.Outcome outcome = guard.execute(request, () -> {
            runs.incrementAndGet();
            return CREATED;
        });
        assertEquals(1, runs.get());
        assertFalse(outcome.replayed());
        assertEquals(0, pauses.get());
    }

    @Test
    void keyAndHashAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyRequest("", KEY, "POST /x", new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyRequest("u", null, "POST /x", new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyRequest("u", KEY, " ", new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyRequest("u", KEY, "POST /x", new byte[31]));
    }

    @Test
    void headerParsingAcceptsOnlyVersion4Uuid() {
        assertEquals(KEY, IdempotencyRequest.parseKey("3f2b8c6e-1a4d-4e0b-9d7a-5c1e2f3a4b5c"));
        assertNull(IdempotencyRequest.parseKey(null));
        assertNull(IdempotencyRequest.parseKey(""));
        assertNull(IdempotencyRequest.parseKey("not-a-uuid"));
        // версия 7 в ключе клиента не принимается: ADR-006 требует UUID версии 4
        assertNull(IdempotencyRequest.parseKey("0199e0a0-0000-7000-8000-000000000201"));
        assertNull(IdempotencyRequest.parseKey("3f2b8c6e1a4d4e0b9d7a5c1e2f3a4b5c"));
    }

    @Test
    void responseIsSuccessfulOnlyFor2xx() {
        assertTrue(new StoredResponse(200, null).successful());
        assertTrue(new StoredResponse(204, "").successful());
        assertFalse(new StoredResponse(301, null).successful());
        assertFalse(new StoredResponse(409, "{}").successful());
        assertNull(new StoredResponse(204, "  ").body());
    }
}
