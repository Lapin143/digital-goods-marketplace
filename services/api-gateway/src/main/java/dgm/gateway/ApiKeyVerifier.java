package dgm.gateway;

import dgm.kit.trace.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Проверка API-ключа продавца на шлюзе (ADR-027, п. 3 и 5; Р-185, Р-186).
 *
 * <ol>
 *   <li><b>Формат.</b> {@code dgm_} и 40 знаков base64url. Правдоподобное значение чужого формата не доходит ни до Redis, ни до
 *       {@code catalog-service}: перебор не нагружает соседей.
 *   <li><b>Хеш.</b> Дальше работает только SHA-256 значения (64 шестнадцатеричных знака): в кэше, в вызове соседу и в метриках самого
 *       значения нет.
 *   <li><b>Память шлюза.</b> Ответ помнится в Redis: действующий ключ {@code api-key-cache-seconds} (30 по умолчанию), отказ короче (5
 *       секунд). Redis не отвечает: это промах, проверка идёт к каталогу (кэш ускоряет, но не решает).
 *   <li><b>Каталог.</b> {@code checkApiKey}. Одно значение, которое проверяют сразу много запросов, даёт один вызов (запросы
 *       ждут общий ответ). Каталог недоступен: {@link Verdict.Unavailable}, запрос с неизвестным ключом получает 503; ключи из кэша
 *       проходят до конца срока (доступность важнее точности, ADR-021).
 *   <li><b>Статус продавца.</b> Каталог отвечает {@code valid: true} и для ключа заблокированного продавца: решает шлюз. Ключ
 *       принимается, только если профиль {@code approved} (условие U3). Итог (в том числе отказ из-за статуса) кэшируется целиком.
 * </ol>
 *
 * <p>Отозванный ключ работает до конца срока памяти: шлюз не подписан на события отзыва (ADR-027, последствия).
 */
public final class ApiKeyVerifier {

    /** Значение ключа: префикс и 30 случайных байт в base64url без заполнения. Тот же шаблон у выпуска ключей в catalog-service. */
    private static final Pattern WELL_FORMED = Pattern.compile("^dgm_[A-Za-z0-9_-]{40}$");

    /** Статус профиля продавца, при котором ключ принимается (раздел «Статусы» ADR-027). */
    static final String APPROVED = "approved";

    private static final Duration LOG_PERIOD = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyVerifier.class);

    /** Итог проверки. */
    public sealed interface Verdict {

        /** Ключ действителен, продавец одобрен. */
        record Accepted(String apiKeyId, String sellerId) implements Verdict {
        }

        /** Ключ не принят: {@link Reason#INVALID} (формат, неизвестный, отозванный: ответ один) или статус профиля. */
        record Rejected(Reason reason) implements Verdict {
        }

        /** Проверить не удалось: каталог не ответил. Такой итог не кэшируется. */
        record Unavailable() implements Verdict {
        }
    }

    /** Причина отказа. */
    public enum Reason {
        /** Значение не того формата, неизвестный ключ или отозванный: клиенту один ответ 401 без подсказки. */
        INVALID,
        /** Ключ действующий, но профиль продавца не одобрен (заблокирован, на проверке): 403. */
        SELLER_NOT_APPROVED
    }

    /** Ответ {@code checkApiKey}. */
    public record CatalogAnswer(boolean valid, String apiKeyId, String sellerId, String sellerStatus) {
    }

    /** Вызов {@code checkApiKey} у каталога. Ошибка в потоке значит «каталог не ответил». */
    public interface CatalogCheck {

        Mono<CatalogAnswer> check(String keyHash, TraceContext trace);
    }

    private final ApiKeyCache cache;
    private final CatalogCheck catalog;
    private final GatewayProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, Mono<Verdict>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong lastUnavailableLog = new AtomicLong(Long.MIN_VALUE);

    public ApiKeyVerifier(ApiKeyCache cache, CatalogCheck catalog, GatewayProperties properties, MeterRegistry meters, Clock clock) {
        this.cache = cache;
        this.catalog = catalog;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /** Значение подходит под формат ключа (проверка до любого обращения к соседям). */
    public static boolean wellFormed(String value) {
        return value != null && WELL_FORMED.matcher(value).matches();
    }

    /** SHA-256 значения: 64 шестнадцатеричных знака в нижнем регистре, как ждёт {@code checkApiKey}. */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 есть в каждой JVM", e);
        }
    }

    public Mono<Verdict> verify(String value, TraceContext trace) {
        if (!wellFormed(value)) {
            count("local", "malformed");
            return Mono.just(new Verdict.Rejected(Reason.INVALID));
        }
        String hash = hash(value);
        return cache.get(hash)
                .timeout(properties.getLimiterTimeout())
                .onErrorResume(e -> {
                    meters.counter("dgm.gateway.apikey.cache.failures", "op", "get").increment();
                    return Mono.empty();
                })
                .doOnNext(verdict -> count("cache", result(verdict)))
                .switchIfEmpty(Mono.defer(() -> load(hash, trace)));
    }

    /** Один вызов каталога на значение, которое проверяют сразу несколько запросов. */
    private Mono<Verdict> load(String hash, TraceContext trace) {
        return inFlight.computeIfAbsent(hash, h -> fetch(h, trace).doFinally(signal -> inFlight.remove(h)).cache());
    }

    private Mono<Verdict> fetch(String hash, TraceContext trace) {
        return catalog.check(hash, trace)
                .timeout(properties.getApiKeyCheckTimeout())
                .map(ApiKeyVerifier::verdictOf)
                .flatMap(verdict -> remember(hash, verdict).thenReturn(verdict))
                .doOnNext(verdict -> count("catalog", result(verdict)))
                .onErrorResume(e -> {
                    count("catalog", "unavailable");
                    noteUnavailable(e);
                    return Mono.just(new Verdict.Unavailable());
                });
    }

    private Mono<Void> remember(String hash, Verdict verdict) {
        Duration ttl = verdict instanceof Verdict.Accepted ? properties.apiKeyTtl() : properties.apiKeyNegativeTtl();
        return cache.put(hash, verdict, ttl)
                .timeout(properties.getLimiterTimeout())
                .onErrorResume(e -> {
                    meters.counter("dgm.gateway.apikey.cache.failures", "op", "put").increment();
                    return Mono.empty();
                });
    }

    /**
     * Ответ каталога в итог. Действующий ключ неодобренного продавца отклоняется здесь, а не в сервисе: сервис видит только личность
     * от шлюза и статуса профиля не знает (F5-6).
     */
    static Verdict verdictOf(CatalogAnswer answer) {
        if (!answer.valid()) {
            return new Verdict.Rejected(Reason.INVALID);
        }
        if (answer.apiKeyId() == null || answer.sellerId() == null || answer.sellerStatus() == null) {
            throw new IllegalStateException("Каталог признал ключ действительным, но не назвал ключ, продавца или статус");
        }
        return APPROVED.equals(answer.sellerStatus()) ? new Verdict.Accepted(answer.apiKeyId(), answer.sellerId())
                : new Verdict.Rejected(Reason.SELLER_NOT_APPROVED);
    }

    private static String result(Verdict verdict) {
        return switch (verdict) {
            case Verdict.Accepted accepted -> "accepted";
            case Verdict.Rejected rejected -> rejected.reason() == Reason.INVALID ? "invalid" : "seller_not_approved";
            case Verdict.Unavailable unavailable -> "unavailable";
        };
    }

    private void count(String source, String result) {
        meters.counter("dgm.gateway.apikey.checks", "source", source, "result", result).increment();
    }

    /** Причина в журнал: только тип ошибки. Тексты ошибок соседей и хеш в журнал не идут. */
    private void noteUnavailable(Throwable e) {
        long now = clock.millis();
        long last = lastUnavailableLog.get();
        if ((last == Long.MIN_VALUE || now - last >= LOG_PERIOD.toMillis()) && lastUnavailableLog.compareAndSet(last, now)) {
            LOG.warn("Проверка API-ключа у catalog-service не удалась, запросы с неизвестным ключом получают 503: {}",
                    e.getClass().getSimpleName());
        }
    }
}
