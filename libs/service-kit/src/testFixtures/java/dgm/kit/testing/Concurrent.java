package dgm.kit.testing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Потоки и защёлка для тестов гонок (шаг 15 фазы 4, test-strategy.md, раздел «Конкуренция»). {@link #race} запускает {@code n} копий
 * одного действия так, чтобы они стартовали вместе: каждый поток сначала сообщает о готовности и ждёт общую защёлку, главный поток
 * открывает её, когда готовы все. Без защёлки первый поток успевает закончить до старта последнего, и гонка не случается.
 *
 * <p>Проверка результата остаётся за тестом, а доказательство отсутствия дублей это запрос к базе ({@code GROUP BY … HAVING count(*) > 1}),
 * а не только ответы потоков. Один прогон ничего не доказывает: задание CI {@code concurrency} повторяет тесты с тегом
 * {@code concurrency} 50 раз подряд.
 *
 * <p>Помощник не берёт соединений с базой сам: пределы ролей (у миграторов 2, у рабочих ролей 8) это забота теста, обращения к базе из
 * потоков идут через сервис (HTTP), а не напрямую.
 */
public final class Concurrent {

    /** Сколько ждать старта потоков и завершения действий. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);

    private Concurrent() {
    }

    /** Действие одного участника гонки; {@code index} это номер участника с нуля. */
    @FunctionalInterface
    public interface Task<T> {
        T run(int index) throws Exception;
    }

    /** Гонка не состоялась или участник завершился ошибкой; причина в {@link #getCause()}. */
    public static final class Failure extends RuntimeException {

        public Failure(String message) {
            super(message);
        }

        public Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** {@link #race(int, Duration, Task)} с предельным временем по умолчанию. */
    public static <T> List<T> race(int threads, Task<T> task) {
        return race(threads, DEFAULT_TIMEOUT, task);
    }

    /**
     * Запускает {@code threads} участников одновременно и возвращает их результаты в порядке номеров.
     *
     * @throws Failure участник бросил исключение (оно в причине), потоки не успели стартовать или закончить за {@code timeout}
     */
    public static <T> List<T> race(int threads, Duration timeout, Task<T> task) {
        if (threads < 2) {
            throw new IllegalArgumentException("Для гонки нужно хотя бы два участника");
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.run(index);
                }));
            }
            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new Failure("Участники гонки не успели стартовать за " + timeout);
            }
            go.countDown();
            long deadline = System.nanoTime() + timeout.toNanos();
            List<T> results = new ArrayList<>(threads);
            for (Future<T> future : futures) {
                results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } catch (ExecutionException e) {
            throw new Failure("Участник гонки завершился ошибкой: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new Failure("Участники гонки не закончили за " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("Ожидание гонки прервано", e);
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }
}
