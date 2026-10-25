package dgm.kit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Цикл слушателя на подставном источнике: порядок применения, пропуск неисправных записей, повторное чтение после сбоя, остановка. */
@Tag("TC-808")
class ConfigListenerTest {

    private static final String RESERVE = "reservation.ttl-seconds";
    private static final String UNPAID = "orders.max-unpaid";

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ConfigRegistry registry = new ConfigRegistry(List.of(), metrics);
    private final ConfigValue reserve = registry.register(RESERVE, 900, 300, 3600);
    private final ConfigValue unpaid = registry.register(UNPAID, 3, 1, 20);
    private ConfigListener listener;

    /** Источник по сценарию: каждая пачка это ответ одного опроса; после сценария возвращает пустые пачки или падает, если так задано. */
    private static final class ScriptedFeed implements ConfigFeed {
        private final Deque<Object> script = new ArrayDeque<>();
        private final AtomicInteger polls = new AtomicInteger();
        private volatile boolean closed;

        ScriptedFeed batch(String... values) {
            script.add(List.of(values));
            return this;
        }

        ScriptedFeed fail(String message) {
            script.add(new IllegalStateException(message));
            return this;
        }

        @Override
        public List<String> poll(Duration timeout) {
            polls.incrementAndGet();
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next instanceof List<?> batch) {
                List<String> values = new ArrayList<>();
                for (Object value : batch) {
                    values.add((String) value);
                }
                return values;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        @Override
        public void wakeup() {
            // источник не блокируется надолго
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private ConfigListener start(ScriptedFeed... feeds) {
        Deque<ScriptedFeed> queue = new ArrayDeque<>(List.of(feeds));
        ScriptedFeed last = feeds[feeds.length - 1];
        listener = new ConfigListener(() -> {
            synchronized (queue) {
                ScriptedFeed next = queue.poll();
                return next != null ? next : last;
            }
        }, registry, metrics, Duration.ofMillis(5), Duration.ofMillis(10));
        listener.start();
        return listener;
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    @AfterEach
    void stop() {
        if (listener != null) {
            listener.close();
        }
    }

    @Test
    void appliesTheMessagesInOrderAndKeepsTheHighestVersion() throws Exception {
        ScriptedFeed feed = new ScriptedFeed()
                .batch(ConfigMessages.changed(RESERVE, 1000, 2), ConfigMessages.changed(UNPAID, 5, 2))
                .batch(ConfigMessages.changed(RESERVE, 1200, 3));
        start(feed);

        assertTrue(await(() -> reserve.version() == 3));
        assertEquals(1200, reserve.get());
        assertEquals(5, unpaid.get());
        assertTrue(listener.isRunning());
    }

    @Test
    void anUnreadableRecordIsSkippedAndTheNextOneIsStillApplied() throws Exception {
        start(new ScriptedFeed().batch("not json", ConfigMessages.changed(RESERVE, 1000, 2), "{}", ConfigMessages.changed(UNPAID, 7, 2)));

        assertTrue(await(() -> unpaid.version() == 2));
        assertEquals(1000, reserve.get());
        assertEquals(7, unpaid.get());
        assertEquals(2, metrics.get("config_invalid_total").counter().count());
    }

    @Test
    void aFailingSourceIsReopenedAndReadFromTheStartWhichChangesNothingTwice() throws Exception {
        ScriptedFeed first = new ScriptedFeed().batch(ConfigMessages.changed(RESERVE, 1000, 2)).fail("брокер недоступен");
        ScriptedFeed second = new ScriptedFeed()
                .batch(ConfigMessages.changed(RESERVE, 1000, 2), ConfigMessages.changed(UNPAID, 9, 3));
        start(first, second);

        assertTrue(await(() -> unpaid.version() == 3), "после сбоя источник открыт заново и дочитан до конца");
        assertTrue(first.closed, "упавший источник закрыт");
        assertEquals(1000, reserve.get());
        assertEquals(1, metrics.get("config_listener_failures_total").counter().count());
        assertEquals(1, metrics.get("config_applied_total").tag("key", RESERVE).counter().count(), "версия 2 применена один раз");
        assertEquals(1, metrics.get("config_rejected_total").tag("key", RESERVE).tag("reason", "duplicate").counter().count());
    }

    @Test
    void aSourceThatCannotBeOpenedDoesNotStopTheServiceAndTheSettingsKeepWorking() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        listener = new ConfigListener(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("нет Kafka");
        }, registry, metrics, Duration.ofMillis(5), Duration.ofMillis(10));
        listener.start();

        assertTrue(await(() -> attempts.get() >= 3), "слушатель повторяет попытки");
        assertTrue(listener.isRunning());
        assertEquals(900, reserve.get(), "значение из настроек работает");
        assertEquals(0, reserve.version());
    }

    @Test
    void closeStopsTheThreadAndClosesTheSource() throws Exception {
        ScriptedFeed feed = new ScriptedFeed();
        start(feed);
        assertTrue(await(() -> feed.polls.get() > 2));

        listener.close();

        assertFalse(listener.isRunning());
        assertTrue(feed.closed, "источник закрывается в потоке слушателя");
        int polls = feed.polls.get();
        Thread.sleep(50);
        assertEquals(polls, feed.polls.get(), "после остановки опросов нет");
        listener.close();
    }

    @Test
    void startingTwiceDoesNotStartASecondThread() throws Exception {
        ScriptedFeed feed = new ScriptedFeed();
        start(feed);
        listener.start();
        long threads = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("config-listener") && t.isAlive()).count();
        assertEquals(1, threads);
    }
}
