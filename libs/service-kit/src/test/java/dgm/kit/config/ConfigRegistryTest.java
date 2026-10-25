package dgm.kit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.config.ConfigRegistry.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Применение значений параметров (Р-168, TC-808): версия, границы, INV-07, закреплённые и неизвестные ключи, метрики. */
@Tag("TC-808")
class ConfigRegistryTest {

    private static final String SESSION = "payment-session.ttl-seconds";
    private static final String RESERVE = "reservation.ttl-seconds";
    private static final String UNPAID = "orders.max-unpaid";

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private ConfigRegistry registry(String... pinned) {
        return new ConfigRegistry(List.of(pinned), metrics);
    }

    private ConfigRegistry pair(String... pinned) {
        ConfigRegistry registry = registry(pinned);
        registry.register(SESSION, 720, 60, 3599);
        registry.register(RESERVE, 900, 300, 3600);
        registry.requireShorter(SESSION, RESERVE);
        return registry;
    }

    private double count(String name, String... tags) {
        var counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void valueFromTheSettingsWorksUntilTheFirstMessage() {
        ConfigRegistry registry = registry();
        ConfigValue value = registry.register(UNPAID, 3, 1, 20);
        assertEquals(3, value.get());
        assertEquals(3, value.intValue());
        assertEquals(0, value.version());
        assertEquals(Duration.ofSeconds(3), value.seconds());
        assertEquals(3, value.defaultValue());
    }

    @Test
    void higherVersionIsAppliedAndEqualOrLowerIsNot() {
        ConfigRegistry registry = registry();
        ConfigValue value = registry.register(UNPAID, 3, 1, 20);

        assertEquals(Outcome.APPLIED, registry.apply(UNPAID, 7, 2));
        assertEquals(7, value.get());
        assertEquals(2, value.version());

        assertEquals(Outcome.DUPLICATE, registry.apply(UNPAID, 9, 2), "та же версия это повтор");
        assertEquals(Outcome.STALE, registry.apply(UNPAID, 5, 1), "меньшая версия устарела");
        assertEquals(7, value.get(), "значение осталось от версии 2");
        assertEquals(Outcome.APPLIED, registry.apply(UNPAID, 8, 5), "версия может расти скачком: промежуточные сжаты темой");
        assertEquals(8, value.get());

        assertEquals(2, count("config_applied_total", "key", UNPAID), "версии 2 и 5");
    }

    @Test
    void metricsCountAppliedRejectedAndExposeTheVersion() {
        ConfigRegistry registry = registry();
        registry.register(UNPAID, 3, 1, 20);
        registry.apply(UNPAID, 7, 2);
        registry.apply(UNPAID, 7, 2);
        registry.apply(UNPAID, 5, 1);
        registry.apply(UNPAID, 0, 3);
        registry.apply("no.such-key", 1, 1);

        assertEquals(1, count("config_applied_total", "key", UNPAID));
        assertEquals(1, count("config_rejected_total", "key", UNPAID, "reason", "duplicate"));
        assertEquals(1, count("config_rejected_total", "key", UNPAID, "reason", "stale-config"));
        assertEquals(1, count("config_rejected_total", "key", UNPAID, "reason", "out-of-range"));
        assertEquals(1, count("config_ignored_total", "key", "no.such-key"));
        assertEquals(2.0, metrics.get("config_version").tag("key", UNPAID).gauge().value(), "версия последнего применённого сообщения");
    }

    @Test
    void valueOutsideTheBoundsIsRefusedAndDoesNotMoveTheVersion() {
        ConfigRegistry registry = registry();
        ConfigValue value = registry.register(UNPAID, 3, 1, 20);
        assertEquals(Outcome.OUT_OF_RANGE, registry.apply(UNPAID, 0, 2));
        assertEquals(Outcome.OUT_OF_RANGE, registry.apply(UNPAID, 21, 2));
        assertEquals(3, value.get());
        assertEquals(0, value.version(), "отклонённое сообщение версию не двигает");
        assertEquals(Outcome.APPLIED, registry.apply(UNPAID, 20, 2), "верхняя граница включена, версия 2 ещё свободна");
        assertEquals(Outcome.APPLIED, registry.apply(UNPAID, 1, 3), "нижняя граница включена");
    }

    @Test
    void unknownKeyIsIgnoredWithAMetric() {
        ConfigRegistry registry = registry();
        registry.register(UNPAID, 3, 1, 20);
        assertEquals(Outcome.UNKNOWN, registry.apply("support.requests-per-hour", 9, 1));
        assertTrue(registry.find("support.requests-per-hour").isEmpty());
        assertEquals(1, count("config_ignored_total", "key", "support.requests-per-hour"));
        assertEquals(0, metrics.find("config_rejected_total").counters().size(), "чужой ключ не считается отказом");
    }

    @Test
    void pinnedKeyIsNeverApplied() {
        ConfigRegistry registry = registry(RESERVE);
        ConfigValue reserve = registry.register(RESERVE, 30, 5, 3600);
        ConfigValue other = registry.register(UNPAID, 3, 1, 20);
        assertEquals(Outcome.PINNED, registry.apply(RESERVE, 900, 4));
        assertEquals(30, reserve.get());
        assertEquals(0, reserve.version());
        assertEquals(1, count("config_pinned_total", "key", RESERVE));
        assertEquals(Outcome.APPLIED, registry.apply(UNPAID, 5, 1), "остальные ключи применяются");
        assertEquals(5, other.get());
        assertEquals(Set.of(RESERVE), registry.pinnedKeys());
    }

    @Test
    void sessionMustStayStrictlyShorterThanTheReserveInBothDirections() {
        ConfigRegistry registry = pair();
        // Соседи уже пришли из темы: сравнение идёт с их текущими значениями
        assertEquals(Outcome.APPLIED, registry.apply(RESERVE, 1000, 2));
        assertEquals(Outcome.APPLIED, registry.apply(SESSION, 600, 2));

        assertEquals(Outcome.INVARIANT, registry.apply(SESSION, 1000, 3), "сессия равна резерву");
        assertEquals(Outcome.INVARIANT, registry.apply(SESSION, 1500, 3), "сессия длиннее резерва");
        assertEquals(Outcome.INVARIANT, registry.apply(RESERVE, 600, 3), "резерв равен сессии");
        assertEquals(Outcome.INVARIANT, registry.apply(RESERVE, 300, 3), "резерв короче сессии");
        assertEquals(600, registry.find(SESSION).orElseThrow().get());
        assertEquals(1000, registry.find(RESERVE).orElseThrow().get());
        assertEquals(2, registry.find(SESSION).orElseThrow().version(), "отклонённое сообщение версию не двигает");

        assertEquals(Outcome.APPLIED, registry.apply(SESSION, 999, 3), "на единицу короче резерва");
        assertEquals(Outcome.APPLIED, registry.apply(RESERVE, 1000, 3), "тот же резерв новой версии допустим");
        assertEquals(2, count("config_rejected_total", "key", SESSION, "reason", "inv-07"));
        assertEquals(2, count("config_rejected_total", "key", RESERVE, "reason", "inv-07"));
    }

    @Test
    void marginOfThePairIsHeldInBothDirectionsAndDefaultsToStrictlyShorter() {
        ConfigRegistry registry = registry();
        registry.register(RESERVE, 900, 1, 86_400);
        registry.register(SESSION, 720, 1, 86_400);
        registry.requireShorter(SESSION, RESERVE, 60);
        assertEquals(Outcome.APPLIED, registry.apply(RESERVE, 1000, 2));
        assertEquals(Outcome.APPLIED, registry.apply(SESSION, 940, 2), "ровно с запасом 60 секунд");
        assertEquals(Outcome.INVARIANT, registry.apply(SESSION, 941, 3), "запас меньше 60 секунд");
        assertEquals(Outcome.INVARIANT, registry.apply(RESERVE, 999, 3), "резерв съедает запас");
        assertEquals(Outcome.APPLIED, registry.apply(RESERVE, 1001, 3));

        ConfigRegistry zero = registry();
        zero.register(RESERVE, 900, 1, 86_400);
        zero.register(SESSION, 720, 1, 86_400);
        zero.requireShorter(SESSION, RESERVE, 0);
        assertEquals(Outcome.APPLIED, zero.apply(RESERVE, 900, 2));
        assertEquals(Outcome.INVARIANT, zero.apply(SESSION, 900, 2), "нулевой запас это всё равно строго короче");
        assertEquals(Outcome.APPLIED, zero.apply(SESSION, 899, 2));
    }

    @Test
    void settingsWithoutTheMarginStopTheStartupWithAClearReason() {
        ConfigRegistry registry = registry();
        registry.register(RESERVE, 900, 1, 86_400);
        registry.register(SESSION, 850, 1, 86_400);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry.requireShorter(SESSION, RESERVE, 60));
        assertTrue(e.getMessage().contains("INV-07"), e.getMessage());
        assertTrue(e.getMessage().contains("850") && e.getMessage().contains("900"), e.getMessage());
    }

    /**
     * Сжатая тема хранит последнее значение каждого ключа в порядке записи. Если сессию правили раньше резерва, при чтении с начала
     * первой придёт сессия, допустимая относительно резерва на тот момент, но не относительно значения из настроек.
     */
    @Test
    void neighbourFromTheSettingsDoesNotBlockTheFirstValueReadFromTheTopic() {
        ConfigRegistry registry = pair();
        // в настройках: сессия 720, резерв 900; в теме: сессия 1500 (версия 3), затем резерв 1600 (версия 4)
        assertEquals(Outcome.APPLIED, registry.apply(SESSION, 1500, 3), "резерв ещё не пришёл, сравнивать не с чем");
        assertEquals(Outcome.APPLIED, registry.apply(RESERVE, 1600, 4));
        assertTrue(registry.find(SESSION).orElseThrow().get() < registry.find(RESERVE).orElseThrow().get());
        // и наоборот
        ConfigRegistry other = pair();
        assertEquals(Outcome.APPLIED, other.apply(RESERVE, 400, 3));
        assertEquals(Outcome.APPLIED, other.apply(SESSION, 300, 4));
    }

    @Test
    void registrationRefusesWhatWouldBreakTheServiceAtStart() {
        ConfigRegistry registry = registry();
        registry.register(UNPAID, 3, 1, 20);
        assertThrows(IllegalStateException.class, () -> registry.register(UNPAID, 3, 1, 20), "ключ уже зарегистрирован");
        assertThrows(IllegalStateException.class, () -> registry.register("a.b", 0, 1, 20), "значение из настроек вне границ");
        assertThrows(IllegalArgumentException.class, () -> registry.register("a.c", 5, 9, 1), "минимум больше максимума");
        assertThrows(IllegalArgumentException.class, () -> registry.register(" ", 5, 1, 9));
        assertThrows(IllegalStateException.class, () -> registry.requireShorter(SESSION, RESERVE), "ключей нет");

        ConfigRegistry broken = registry();
        broken.register(SESSION, 900, 60, 3599);
        broken.register(RESERVE, 900, 300, 3600);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> broken.requireShorter(SESSION, RESERVE));
        assertTrue(e.getMessage().contains("INV-07"), e.getMessage());
    }

    @Test
    void aParameterSharedByTwoModulesIsOneValue() {
        ConfigRegistry registry = registry();
        ConfigValue first = registry.obtain("moderation.review-deadline-seconds", 259_200, 1, 1_209_600);
        ConfigValue second = registry.obtain("moderation.review-deadline-seconds", 259_200, 1, 1_209_600);
        assertSame(first, second);
        registry.apply("moderation.review-deadline-seconds", 7200, 2);
        assertEquals(7200, second.get(), "оба модуля видят одно значение");
        assertThrows(IllegalStateException.class, () -> registry.obtain("moderation.review-deadline-seconds", 259_200, 1, 100), "другие границы");
        assertThrows(IllegalStateException.class, () -> registry.obtain("moderation.review-deadline-seconds", 10, 1, 1_209_600), "другое значение");
        assertThrows(IllegalStateException.class, () -> registry.register("moderation.review-deadline-seconds", 259_200, 1, 1_209_600), "register строгий");
    }

    @Test
    void messagesFromManyThreadsEndWithTheHighestVersion() throws Exception {
        ConfigRegistry registry = registry();
        ConfigValue value = registry.register(UNPAID, 1, 1, 1_000_000);
        int threads = 8;
        int perThread = 500;
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int offset = t;
            Thread worker = new Thread(() -> {
                // каждая нить шлёт свои версии в обратном порядке: большая версия приходит раньше меньшей
                for (int i = perThread; i >= 1; i--) {
                    int version = i * threads + offset;
                    registry.apply(UNPAID, version, version);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        assertEquals(perThread * threads + threads - 1, value.version(), "осталась наибольшая версия, устаревшие не затёрли её");
        assertEquals(value.version(), value.get());
        double applied = count("config_applied_total", "key", UNPAID);
        double rejected = count("config_rejected_total", "key", UNPAID, "reason", "stale-config");
        assertEquals(threads * perThread, applied + rejected, "каждое сообщение либо применено, либо отклонено как устаревшее");
    }
}
