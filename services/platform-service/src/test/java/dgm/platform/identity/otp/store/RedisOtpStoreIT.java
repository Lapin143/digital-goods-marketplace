package dgm.platform.identity.otp.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.redis.KitRedis;
import dgm.kit.redis.RedisSettings;
import dgm.kit.secret.SecretFiles;
import dgm.kit.testing.Stand;
import dgm.kit.tls.TlsMaterial;
import dgm.platform.identity.otp.service.OtpStore;
import dgm.platform.identity.otp.service.OtpStore.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Скрипты Lua хранилища кодов на настоящем Redis стенда под пользователем ACL {@code platform} (ключи {@code platform:*}, без команды
 * TIME): окна запросов, погашение кода, замок номера и главная проверка NFT-3.7: из 50 одновременных попыток с верным кодом успешна
 * ровно одна. Время передаётся параметром, поэтому сроки проверяются без ожидания. Нужен стенд с отладочным портом Redis:
 * {@code make up SET=dev-kit DEBUG=1}; адрес меняют {@code DGM_REDIS_HOST}, {@code DGM_REDIS_PORT}.
 */
@Tag("integration")
class RedisOtpStoreIT {

    private static final String PREFIX = "platform:it:" + UUID.randomUUID() + ":";
    private static final Instant T0 = Instant.parse("2026-10-11T12:00:00Z");
    private static final List<String> USED = new ArrayList<>();

    private static KitRedis redis;
    private static RedisOtpStore store;

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeAll
    static void connect() {
        TlsMaterial tls = TlsMaterial.forService(Stand.secrets(), "platform-service");
        RedisSettings settings = new RedisSettings(env("DGM_REDIS_HOST", "localhost"), Integer.parseInt(env("DGM_REDIS_PORT", "16379")),
                "platform", Duration.ofSeconds(3), Duration.ofSeconds(5));
        redis = KitRedis.create(settings, () -> SecretFiles.read("redis_platform").reveal().toCharArray(), tls);
        store = new RedisOtpStore(redis);
    }

    @AfterAll
    static void close() {
        for (String key : USED) {
            try {
                redis.delete(key);
            } catch (ProblemException e) {
                // очистка не должна скрывать итог тестов
            }
        }
        redis.close();
    }

    private static String key(String name) {
        String key = PREFIX + name;
        USED.add(key);
        return key;
    }

    private static OtpStore.Code code(String hash, Instant expiresAt, int attempts, String userId, String confirmationId, String phone) {
        return new OtpStore.Code(hash, expiresAt, attempts, userId, confirmationId, phone);
    }

    private static OtpStore.Redeem redeem(String key, String hash, String confirmationId) {
        return new OtpStore.Redeem(key, hash, confirmationId, Optional.empty());
    }

    // ---- окна запросов ----

    @Test
    void slidingWindowAdmitsUpToTheLimitThenDeniesAndFreesAsTimePasses() {
        String window = key("window");
        List<OtpStore.Window> windows = List.of(OtpStore.Window.sliding(window, Duration.ofMinutes(10), 3));

        OtpStore.Admission first = store.admit(windows, T0);
        OtpStore.Admission second = store.admit(windows, T0.plusSeconds(30));
        OtpStore.Admission third = store.admit(windows, T0.plusSeconds(60));

        assertTrue(first.allowed() && second.allowed() && third.allowed());
        assertEquals(2, first.remaining());
        assertEquals(1, second.remaining());
        assertEquals(0, third.remaining());
        assertEquals(Duration.ofSeconds(540), third.resendAfter(), "новый код можно запросить, когда выйдет из окна первый запрос");

        OtpStore.Admission denied = store.admit(windows, T0.plusSeconds(90));
        assertFalse(denied.allowed());
        assertEquals(Duration.ofSeconds(510), denied.retryAfter());

        assertFalse(store.admit(windows, T0.plusSeconds(599)).allowed());
        assertTrue(store.admit(windows, T0.plusSeconds(600)).allowed(), "ровно через десять минут первый запрос вышел из окна");
    }

    @Test
    void deniedRequestConsumesNothingInAnyWindow() {
        OtpStore.Window narrow = OtpStore.Window.sliding(key("narrow"), Duration.ofMinutes(10), 1);
        OtpStore.Window wide = OtpStore.Window.sliding(key("wide"), Duration.ofMinutes(10), 3);

        assertTrue(store.admit(List.of(narrow, wide), T0).allowed());
        for (int i = 0; i < 5; i++) {
            assertFalse(store.admit(List.of(narrow, wide), T0.plusSeconds(i + 1)).allowed());
        }

        assertTrue(store.admit(List.of(wide), T0.plusSeconds(10)).allowed(), "в широком окне занято одно место из трёх");
        assertTrue(store.admit(List.of(wide), T0.plusSeconds(11)).allowed());
        assertFalse(store.admit(List.of(wide), T0.plusSeconds(12)).allowed(), "отказы в узком окне места в широком не заняли");
    }

    @Test
    void fixedCounterStartsAtTheFirstRequestAndDeniesAtTheCap() {
        List<OtpStore.Window> cap = List.of(OtpStore.Window.counter(key("cap"), Duration.ofHours(24), 2));

        assertTrue(store.admit(cap, T0).allowed());
        assertTrue(store.admit(cap, T0).allowed());
        OtpStore.Admission denied = store.admit(cap, T0);

        assertFalse(denied.allowed());
        assertTrue(denied.retryAfter().compareTo(Duration.ofHours(23)) > 0 && denied.retryAfter().compareTo(Duration.ofHours(24)) <= 0,
                "до конца суток: " + denied.retryAfter());
    }

    @Test
    void windowsOfDifferentKindsWorkInOneCall() {
        List<OtpStore.Window> windows = List.of(
                OtpStore.Window.sliding(key("mix-account"), Duration.ofMinutes(10), 3),
                OtpStore.Window.sliding(key("mix-ip"), Duration.ofHours(1), 10),
                OtpStore.Window.counter(key("mix-cap"), Duration.ofHours(24), 1000));

        OtpStore.Admission admission = store.admit(windows, T0);

        assertTrue(admission.allowed());
        assertEquals(2, admission.remaining());
    }

    @Test
    void fiftyParallelAdmissionsLetExactlyTheLimitThrough() throws Exception {
        List<OtpStore.Window> windows = List.of(OtpStore.Window.sliding(key("parallel-window"), Duration.ofMinutes(10), 10));

        List<Boolean> results = parallel(50, () -> store.admit(windows, T0).allowed());

        assertEquals(10, results.stream().filter(Boolean::booleanValue).count());
    }

    // ---- проверка кода ----

    @Test
    void validCodeIsRedeemedOnceAndStoredOnlyAsHash() {
        String key = key("code-valid");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));

        String raw = redis.get(key).orElseThrow();
        assertTrue(raw.contains("hash-1"));
        assertFalse(raw.contains("808080"), "кода в записи нет");

        OtpStore.Redemption valid = store.redeem(redeem(key, "hash-1", "conf-1"), T0.plusSeconds(10));
        assertEquals(Result.VALID, valid.result());
        assertEquals("user-1", valid.userId());
        assertEquals("+79001234567", valid.phone());
        assertEquals(Result.ABSENT, store.redeem(redeem(key, "hash-1", "conf-1"), T0.plusSeconds(11)).result(), "код одноразовый");
        assertEquals(Optional.empty(), redis.get(key));
    }

    @Test
    void attemptsRunOutOnTheFifthWrongCodeAndTheCodeIsGone() {
        String key = key("code-attempts");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));

        for (int left = 4; left >= 1; left--) {
            OtpStore.Redemption wrong = store.redeem(redeem(key, "wrong", "conf-1"), T0.plusSeconds(10));
            assertEquals(Result.INVALID, wrong.result());
            assertEquals(left, wrong.attemptsLeft());
        }
        assertEquals(Result.EXHAUSTED, store.redeem(redeem(key, "wrong", "conf-1"), T0.plusSeconds(10)).result());
        assertEquals(Result.ABSENT, store.redeem(redeem(key, "hash-1", "conf-1"), T0.plusSeconds(11)).result(), "верный код после пятой неудачи не принимается");
    }

    @Test
    void codeExpiresStrictlyAfterItsTimeNotAtIt() {
        String key = key("code-expiry");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));

        assertEquals(Result.EXPIRED, store.redeem(redeem(key, "hash-1", "conf-1"), T0.plusSeconds(301)).result());
        String second = key("code-expiry-edge");
        store.put(second, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));
        assertEquals(Result.VALID, store.redeem(redeem(second, "hash-1", "conf-1"), T0.plusSeconds(300)).result());
    }

    @Test
    void anotherConfirmationIsRefusedWithoutTouchingTheRecord() {
        String key = key("code-other");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));

        assertEquals(Result.OTHER, store.redeem(redeem(key, "hash-1", "conf-2"), T0).result());
        assertEquals(Result.VALID, store.redeem(redeem(key, "hash-1", "conf-1"), T0).result());
    }

    @Test
    void decoyRecordNeverRedeemsEvenWithAMatchingHash() {
        String key = key("code-decoy");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "", "", ""), Duration.ofMinutes(15));

        assertEquals(Result.INVALID, store.redeem(redeem(key, "hash-1", ""), T0).result());
    }

    @Test
    void retentionRemovesTheRecordAfterTheTtl() throws Exception {
        String key = key("code-ttl");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMillis(300));

        Thread.sleep(600);

        assertEquals(Result.ABSENT, store.redeem(redeem(key, "hash-1", "conf-1"), T0).result());
    }

    @Test
    void removeDeletesTheRecord() {
        String key = key("code-remove");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));

        store.remove(key);

        assertEquals(Result.ABSENT, store.redeem(redeem(key, "hash-1", "conf-1"), T0).result());
    }

    // ---- замок номера ----

    @Test
    void threeBurnedCodesLockTheNumberAndACorrectCodeResetsTheCount() {
        String codeKey = key("login-code");
        OtpStore.Tracking tracking = new OtpStore.Tracking(key("login-burn"), key("login-lock"), 3, Duration.ofHours(1), Duration.ofHours(1));

        for (int round = 0; round < 2; round++) {
            store.put(codeKey, code("hash-1", T0.plusSeconds(300), 2, "user-1", "", ""), Duration.ofMinutes(15));
            assertEquals(Result.INVALID, store.redeem(loginRedeem(codeKey, "wrong", tracking), T0).result());
            OtpStore.Redemption burned = store.redeem(loginRedeem(codeKey, "wrong", tracking), T0);
            assertEquals(Result.EXHAUSTED, burned.result());
            assertEquals(Duration.ZERO, burned.lockedFor(), "номер ещё не закрыт");
        }
        store.put(codeKey, code("hash-1", T0.plusSeconds(300), 2, "user-1", "", ""), Duration.ofMinutes(15));
        assertEquals(Result.VALID, store.redeem(loginRedeem(codeKey, "hash-1", tracking), T0).result(), "верный код обнуляет счёт");

        for (int round = 0; round < 3; round++) {
            store.put(codeKey, code("hash-1", T0.plusSeconds(300), 1, "user-1", "", ""), Duration.ofMinutes(15));
            OtpStore.Redemption burned = store.redeem(loginRedeem(codeKey, "wrong", tracking), T0.plusSeconds(round));
            assertEquals(Result.EXHAUSTED, burned.result());
            assertEquals(round == 2 ? Duration.ofHours(1) : Duration.ZERO, burned.lockedFor());
        }

        Optional<Duration> locked = store.lockedFor(tracking.lockKey(), T0.plusSeconds(60));
        assertTrue(locked.isPresent());
        assertEquals(Duration.ofSeconds(3600 - 58), locked.get(), "замок рассчитан от момента третьего погашенного кода");
        store.put(codeKey, code("hash-1", T0.plusSeconds(300), 5, "user-1", "", ""), Duration.ofMinutes(15));
        OtpStore.Redemption refused = store.redeem(loginRedeem(codeKey, "hash-1", tracking), T0.plusSeconds(60));
        assertEquals(Result.LOCKED, refused.result(), "пока номер закрыт, даже верный код не проверяется");
        assertTrue(refused.lockedFor().compareTo(Duration.ofSeconds(3500)) > 0);
        assertEquals(Optional.empty(), store.lockedFor(tracking.lockKey(), T0.plusSeconds(3600 + 2)), "через час после третьего кода замок снят");
    }

    private static OtpStore.Redeem loginRedeem(String codeKey, String hash, OtpStore.Tracking tracking) {
        return new OtpStore.Redeem(codeKey, hash, "", Optional.of(tracking));
    }

    // ---- одновременные попытки (NFT-3.7) ----

    @Test
    void fiftyParallelRedemptionsOfTheRightCodeSucceedExactlyOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            String key = key("race-valid-" + round);
            store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));

            List<Result> results = parallel(50, () -> store.redeem(redeem(key, "hash-1", "conf-1"), T0.plusSeconds(5)).result());

            assertEquals(1, results.stream().filter(r -> r == Result.VALID).count(), "успешна ровно одна попытка");
            assertEquals(49, results.stream().filter(r -> r == Result.ABSENT).count(), "остальные видят погашенный код");
        }
    }

    @Test
    void fiftyParallelWrongAttemptsSpendExactlyFiveAttempts() throws Exception {
        String key = key("race-wrong");
        store.put(key, code("hash-1", T0.plusSeconds(300), 5, "user-1", "conf-1", "+79001234567"), Duration.ofMinutes(15));

        List<Result> results = parallel(50, () -> store.redeem(redeem(key, "wrong", "conf-1"), T0.plusSeconds(5)).result());

        assertEquals(4, results.stream().filter(r -> r == Result.INVALID).count());
        assertEquals(1, results.stream().filter(r -> r == Result.EXHAUSTED).count());
        assertEquals(45, results.stream().filter(r -> r == Result.ABSENT).count());
    }

    @Test
    void markOnceSetsTheMarkOnlyForTheFirstCaller() {
        String key = key("mark");

        assertTrue(store.markOnce(key, Duration.ofMinutes(1)));
        assertFalse(store.markOnce(key, Duration.ofMinutes(1)));
    }

    // ---- помощник ----

    /** Запускает действие в {@code threads} потоках по общему сигналу и собирает результаты. */
    private static <T> List<T> parallel(int threads, java.util.concurrent.Callable<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return action.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
