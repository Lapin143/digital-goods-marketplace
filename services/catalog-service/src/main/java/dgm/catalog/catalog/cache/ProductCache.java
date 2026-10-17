package dgm.catalog.catalog.cache;

import dgm.catalog.catalog.domain.CacheSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Кэш карточек витрины (компонент {@code catalog-cache}, NFT-1.0, ADR-022). В Redis лежит готовое тело ответа {@code getStorefrontProduct}
 * под ключом {@code catalog:product:<id>} со сроком жизни из {@link CacheSettings}.
 *
 * <p>Кэш не источник истины и не условие работы: недоступный Redis, таймаут и отсутствие настроенного адреса дают «в кэше нет», карточка
 * читается из базы. Сброс по изменению (решение модератора, правка опубликованного товара, {@code stock.changed}) идёт после фиксации
 * транзакции и не в потоке запроса: запрос, который меняет товар, Redis не ждёт, а его отказ отвечает только за срок, на который
 * карточка может отстать (срок жизни ключа).
 */
public final class ProductCache implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ProductCache.class);
    static final String PREFIX = "catalog:product:";
    /** Очередь сбросов ограничена: при долгом отказе Redis лишние сбросы отбрасываются, срок жизни ключа догонит. */
    private static final int EVICT_QUEUE = 1000;

    private final CacheBackend backend;
    private final CacheSettings settings;
    private final MeterRegistry meters;
    private final Executor evictor;
    private final ExecutorService owned;

    /** Кэш с собственным потоком сброса. {@code backend == null}: кэш выключен (Redis не настроен). */
    public ProductCache(CacheBackend backend, CacheSettings settings, MeterRegistry meters) {
        this.backend = backend;
        this.settings = settings;
        this.meters = meters;
        if (backend == null) {
            this.owned = null;
            this.evictor = Runnable::run;
        } else {
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(EVICT_QUEUE), runnable -> {
                Thread thread = new Thread(runnable, "catalog-cache-evictor");
                thread.setDaemon(true);
                return thread;
            });
            this.owned = pool;
            this.evictor = pool;
        }
    }

    /** Кэш с заданным исполнителем сбросов: в модульных тестах сброс выполняется сразу. */
    public ProductCache(CacheBackend backend, CacheSettings settings, MeterRegistry meters, Executor evictor) {
        this.backend = backend;
        this.settings = settings;
        this.meters = meters;
        this.evictor = evictor;
        this.owned = null;
    }

    public boolean enabled() {
        return backend != null;
    }

    /** Тело карточки из кэша. Пусто и при промахе, и при отказе хранилища. */
    public Optional<String> get(UUID productId) {
        if (backend == null) {
            return Optional.empty();
        }
        try {
            Optional<String> value = backend.get(PREFIX + productId);
            count("get", value.isPresent() ? "hit" : "miss");
            return value;
        } catch (RuntimeException e) {
            failed("get", e);
            return Optional.empty();
        }
    }

    public void put(UUID productId, String body) {
        if (backend == null) {
            return;
        }
        try {
            backend.set(PREFIX + productId, body, settings.productTtl());
            count("put", "ok");
        } catch (RuntimeException e) {
            failed("put", e);
        }
    }

    /** Сбрасывает карточку, не задерживая вызывающего. Вызывается после фиксации транзакции, которая изменила товар. */
    public void evictLater(UUID productId) {
        if (backend == null) {
            return;
        }
        try {
            evictor.execute(() -> evict(productId));
        } catch (RejectedExecutionException e) {
            count("evict", "dropped");
        }
    }

    void evict(UUID productId) {
        try {
            backend.delete(PREFIX + productId);
            count("evict", "ok");
        } catch (RuntimeException e) {
            failed("evict", e);
        }
    }

    private void count(String operation, String result) {
        meters.counter("catalog_cache_total", "operation", operation, "result", result).increment();
    }

    private void failed(String operation, RuntimeException e) {
        count(operation, "error");
        // Текст ошибки не пишется: в нём могут оказаться адрес и имя пользователя Redis
        LOG.warn("Кэш витрины не ответил ({}): {}", operation, e.getClass().getSimpleName());
    }

    @Override
    public void close() {
        if (owned != null) {
            owned.shutdownNow();
        }
    }
}
