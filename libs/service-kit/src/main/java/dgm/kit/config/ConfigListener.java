package dgm.kit.config;

import dgm.kit.events.InvalidEventException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Слушатель параметров платформы (компонент {@code config-listener}, Р-168): собственный поток, свой источник записей, сообщения
 * применяются к {@link ConfigRegistry}. Поток отдельный от потребителя событий: повтор записи соседней темы на 31 секунду не задерживает
 * применение параметра.
 *
 * <p>Сервис не зависит от слушателя: пока Kafka недоступна или тема пуста, работают значения из {@code application.yml}. Сбой источника
 * не роняет поток: он закрывает источник, ждёт и начинает чтение заново с начала темы (повторное чтение безвредно: применяется только версия
 * выше сохранённой). Запись, которую не удалось разобрать, пропускается со счётчиком {@code config_invalid_total}.
 *
 * <p>Метрики: {@code config_listener_failures_total}, {@code config_invalid_total}.
 */
public final class ConfigListener implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigListener.class);

    private final Supplier<ConfigFeed> feeds;
    private final ConfigRegistry registry;
    private final MeterRegistry metrics;
    private final Duration pollTimeout;
    private final Duration retryPause;

    private volatile boolean running;
    private volatile ConfigFeed current;
    private Thread thread;

    public ConfigListener(Supplier<ConfigFeed> feeds, ConfigRegistry registry, MeterRegistry metrics, Duration pollTimeout, Duration retryPause) {
        this.feeds = feeds;
        this.registry = registry;
        this.metrics = metrics;
        this.pollTimeout = pollTimeout;
        this.retryPause = retryPause;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "config-listener");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }

    private void loop() {
        while (running) {
            ConfigFeed feed = null;
            try {
                feed = feeds.get();
                current = feed;
                while (running) {
                    handle(feed.poll(pollTimeout));
                }
            } catch (RuntimeException e) {
                if (running) {
                    metrics.counter("config_listener_failures_total").increment();
                    LOG.warn("Чтение параметров прервано, значения остаются прежними, повторю через {} с: {}", retryPause.toSeconds(), e.getMessage());
                }
            } finally {
                current = null;
                if (feed != null) {
                    feed.close();
                }
            }
            if (running) {
                pause();
            }
        }
    }

    /** Применяет пачку по порядку; запись, которую не разобрать, пропускается. */
    void handle(List<String> values) {
        for (String value : values) {
            ConfigChange change;
            try {
                change = ConfigChange.parse(value);
            } catch (InvalidEventException e) {
                metrics.counter("config_invalid_total").increment();
                LOG.warn("Запись темы параметров пропущена: {}", e.getMessage());
                continue;
            }
            registry.apply(change.key(), change.value(), change.version());
        }
    }

    private void pause() {
        try {
            Thread.sleep(retryPause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** Останавливает поток; источник закрывается в нём (клиент Kafka не потокобезопасен, кроме {@code wakeup}). */
    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            ConfigFeed feed = current;
            if (feed != null) {
                feed.wakeup();
            }
            thread.interrupt();
            try {
                thread.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }
}
