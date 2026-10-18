package dgm.kit.retry;

import java.time.Duration;

/** Приостановка потока между попытками. Логика повторов не вызывает {@code Thread.sleep} сама: в тестах подставляют запись пауз. */
@FunctionalInterface
public interface Sleeper {

    /** Настоящее ожидание. */
    Sleeper SYSTEM = duration -> Thread.sleep(duration.toMillis(), (int) (duration.toNanos() % 1_000_000));

    void sleep(Duration duration) throws InterruptedException;
}
