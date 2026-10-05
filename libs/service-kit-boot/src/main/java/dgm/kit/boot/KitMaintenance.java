package dgm.kit.boot;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Периодические задания очистки служебных таблиц в одном фоновом потоке. Ошибка одного задания не останавливает остальные. */
public final class KitMaintenance implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KitMaintenance.class);

    private final ScheduledExecutorService executor;

    private KitMaintenance(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    /** Запускает задания: первый запуск через {@code initialDelay}, затем каждые {@code period}. Каждое задание возвращает число удалённых строк. */
    public static KitMaintenance start(Duration initialDelay, Duration period, IntSupplier... jobs) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kit-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            for (IntSupplier job : jobs) {
                try {
                    int removed = job.getAsInt();
                    LOG.info("Очистка служебной таблицы: удалено строк {}", removed);
                } catch (RuntimeException e) {
                    LOG.warn("Очистка служебной таблицы не удалась: {}", e.getMessage());
                }
            }
        }, initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        return new KitMaintenance(executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
