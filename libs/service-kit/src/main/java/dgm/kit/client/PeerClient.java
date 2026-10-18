package dgm.kit.client;

import dgm.kit.retry.RetryPolicy;
import dgm.kit.retry.Sleeper;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент вызова соседа по HTTP (R1, Р-140): внутренние операции сервисов и внешние системы. Один клиент на соседа.
 *
 * <p>Что делает каждый вызов:
 * <ul>
 *   <li>кладёт в заголовки {@code X-Correlation-Id} и {@code traceparent} (новый отрезок текущей трассы) и, если задан,
 *       {@code Idempotency-Key};</li>
 *   <li>ограничивает каждую попытку временем {@link Settings#attemptTimeout()}, а всё вместе (попытки и паузы между ними)
 *       сроком {@link Settings#totalDeadline()}: повтор не растягивает вызов дольше срока, который отведён вызывающему;</li>
 *   <li>повторяет только идемпотентный запрос ({@link PeerRequest#idempotent()}) и только после сетевого сбоя, тайм-аута, ответа 5xx
 *       или 429 (ADR-015); паузы берёт у {@link RetryPolicy} (с разбросом), ожидание делает {@link Sleeper};</li>
 *   <li>считает исходы в окне предохранителя: пока он открыт, вызов не уходит к соседу вовсе. Ответ 3xx/4xx считается успехом
 *       предохранителя (сосед жив и принял решение) и отдаётся как {@link DependencyRejectedException}.</li>
 * </ul>
 *
 * <p>Клиент ничего не знает о транзакциях, но вызывать его из метода с {@code @Transactional} нельзя (правило 9 руководства, проверяется
 * ArchUnit): ожидание ответа соседа держало бы соединение с базой. Адреса, заголовки и тела ответов в журнал и в тексты ошибок не
 * попадают. Метрики: {@code dgm_client_requests_total{peer,outcome}} (на каждую попытку; исходы {@code ok}, {@code rejected},
 * {@code timeout}, {@code io_error}, {@code server_error}, {@code breaker_open}) и {@code dgm_client_breaker_state{peer}}
 * (0 закрыт, 1 полуоткрыт, 2 открыт).
 */
public final class PeerClient {

    private static final Logger LOG = LoggerFactory.getLogger(PeerClient.class);

    /** Предел установки соединения (ADR-015: соединение 1 с, чтение 2 с). */
    private static final Duration CONNECT_LIMIT = Duration.ofSeconds(1);

    /** Заголовки, которые клиент выставляет сам и не позволяет перекрыть. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("idempotency-key", "x-correlation-id", "traceparent",
            "content-type", "host", "content-length");

    /**
     * Настройки клиента одного соседа.
     *
     * @param peer              имя соседа для журнала и метрик, например {@code inventory-service}
     * @param base              базовый адрес без завершающей косой черты
     * @param attemptTimeout    предельное время одной попытки
     * @param totalDeadline     предельное время вызова целиком, с повторами и паузами
     * @param retry             расписание пауз между попытками
     * @param breakerWindow     число последних вызовов в окне предохранителя
     * @param breakerRatio      доля неудач в заполненном окне, после которой предохранитель открывается
     * @param breakerOpenFor    сколько предохранитель остаётся открытым до пробной попытки
     */
    public record Settings(String peer, URI base, Duration attemptTimeout, Duration totalDeadline, RetryPolicy retry,
                           int breakerWindow, double breakerRatio, Duration breakerOpenFor) {

        public Settings {
            Objects.requireNonNull(peer, "peer");
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(retry, "retry");
            if (peer.isBlank()) {
                throw new IllegalArgumentException("Имя соседа не задано");
            }
            if (attemptTimeout == null || attemptTimeout.isZero() || attemptTimeout.isNegative()) {
                throw new IllegalArgumentException("Время попытки должно быть больше нуля");
            }
            if (totalDeadline == null || totalDeadline.compareTo(attemptTimeout) < 0) {
                throw new IllegalArgumentException("Общий срок не может быть меньше времени одной попытки");
            }
            if (breakerWindow < 1 || breakerRatio <= 0 || breakerRatio > 1) {
                throw new IllegalArgumentException("Окно предохранителя от 1 вызова, порог от 0 (не включая) до 1");
            }
            if (breakerOpenFor == null || breakerOpenFor.isZero() || breakerOpenFor.isNegative()) {
                throw new IllegalArgumentException("Пауза предохранителя должна быть больше нуля");
            }
        }

        /**
         * Значения по умолчанию из ADR-015: попытка 2 с, до трёх попыток с паузами 300 мс и 700 мс (разброс 20 %), общий срок равен трём
         * попыткам и наибольшим паузам, предохранитель на окне в 10 вызовов с порогом 50 % и паузой 30 с.
         */
        public static Settings of(String peer, URI base, RandomGenerator random) {
            RetryPolicy retry = RetryPolicy.parse("300ms,700ms", 0.2, random);
            Duration attempt = Duration.ofSeconds(2);
            Duration total = attempt.multipliedBy(retry.maxAttempts()).plus(retry.totalMaxPause());
            return new Settings(peer, base, attempt, total, retry, 10, 0.5, Duration.ofSeconds(30));
        }

        public static Settings of(String peer, URI base) {
            return of(peer, base, RandomGenerator.getDefault());
        }

        public Settings withTimeouts(Duration attemptTimeout, Duration totalDeadline) {
            return new Settings(peer, base, attemptTimeout, totalDeadline, retry, breakerWindow, breakerRatio, breakerOpenFor);
        }

        public Settings withRetry(RetryPolicy retry) {
            return new Settings(peer, base, attemptTimeout, totalDeadline, retry, breakerWindow, breakerRatio, breakerOpenFor);
        }

        public Settings withBreaker(int window, double ratio, Duration openFor) {
            return new Settings(peer, base, attemptTimeout, totalDeadline, retry, window, ratio, openFor);
        }
    }

    private final Settings settings;
    private final HttpClient http;
    private final Clock clock;
    private final Sleeper sleeper;
    private final MeterRegistry metrics;
    private final CircuitBreaker breaker;
    private final String baseText;

    /**
     * @param tls     контекст TLS с клиентским сертификатом сервиса (взаимная проверка, {@code TlsMaterial.mutualContext()}); {@code null}
     *                для обычного HTTP в тестах
     * @param metrics реестр метрик или {@code null}: тогда метрики копятся в локальном реестре
     */
    public PeerClient(Settings settings, SSLContext tls, Clock clock, Sleeper sleeper, MeterRegistry metrics) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.metrics = metrics == null ? new SimpleMeterRegistry() : metrics;
        // Соединение ждём не дольше секунды, чтение до предела попытки (ADR-015)
        Duration connect = settings.attemptTimeout().compareTo(CONNECT_LIMIT) > 0 ? CONNECT_LIMIT : settings.attemptTimeout();
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER);
        if (tls != null) {
            builder.sslContext(tls);
        }
        this.http = builder.build();
        this.breaker = new CircuitBreaker(settings.breakerWindow(), settings.breakerRatio(), settings.breakerOpenFor(), clock);
        String text = settings.base().toString();
        this.baseText = text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
        Gauge.builder("dgm_client_breaker_state", breaker, b -> b.state().ordinal()).tag("peer", settings.peer()).register(this.metrics);
    }

    public String peer() {
        return settings.peer();
    }

    public Settings settings() {
        return settings;
    }

    public CircuitBreaker.State breakerState() {
        return breaker.state();
    }

    /**
     * Выполняет запрос.
     *
     * @return ответ с кодом 2xx
     * @throws DependencyRejectedException    сосед ответил 3xx или 4xx (кроме 408 и 429)
     * @throws DependencyUnavailableException сосед не ответил после всех разрешённых попыток либо предохранитель открыт либо срок исчерпан;
     *                                        для запроса без {@code Idempotency-Key} это значит «результат неизвестен»
     */
    public PeerResponse call(PeerRequest request) {
        Objects.requireNonNull(request, "request");
        int attempts = request.idempotent() ? settings.retry().maxAttempts() : 1;
        Instant deadline = clock.instant().plus(settings.totalDeadline());
        TraceContext trace = TraceContexts.currentOrFresh().child();
        String lastReason = DependencyUnavailableException.DEADLINE;
        int lastStatus = 0;
        Throwable lastCause = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                Duration pause = settings.retry().pause(attempt - 2);
                if (clock.instant().plus(pause).isAfter(deadline)) {
                    throw failure(DependencyUnavailableException.DEADLINE, lastStatus, lastCause, attempt - 1, attempts);
                }
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw failure(DependencyUnavailableException.INTERRUPTED, lastStatus, e, attempt - 1, attempts);
                }
            }
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                throw failure(DependencyUnavailableException.DEADLINE, lastStatus, lastCause, attempt - 1, attempts);
            }
            if (!breaker.tryAcquire()) {
                count(DependencyUnavailableException.BREAKER_OPEN);
                throw new DependencyUnavailableException(settings.peer(), DependencyUnavailableException.BREAKER_OPEN, lastStatus, lastCause);
            }
            Duration timeout = remaining.compareTo(settings.attemptTimeout()) < 0 ? remaining : settings.attemptTimeout();
            HttpResponse<String> response;
            try {
                response = http.send(build(request, trace, timeout), HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException e) {
                breaker.recordFailure();
                count(DependencyUnavailableException.TIMEOUT);
                lastReason = DependencyUnavailableException.TIMEOUT;
                lastCause = e;
                logAttempt(lastReason, 0, attempt, attempts);
                continue;
            } catch (IOException e) {
                breaker.recordFailure();
                count(DependencyUnavailableException.IO_ERROR);
                lastReason = DependencyUnavailableException.IO_ERROR;
                lastCause = e;
                logAttempt(lastReason, 0, attempt, attempts);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                breaker.abandon();
                throw failure(DependencyUnavailableException.INTERRUPTED, lastStatus, e, attempt, attempts);
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                breaker.recordSuccess();
                count("ok");
                return new PeerResponse(status, response.body(), headersOf(response));
            }
            if (status == 429 || status >= 500) {
                breaker.recordFailure();
                count(DependencyUnavailableException.SERVER_ERROR);
                lastReason = DependencyUnavailableException.SERVER_ERROR;
                lastStatus = status;
                lastCause = null;
                logAttempt(lastReason, status, attempt, attempts);
                continue;
            }
            breaker.recordSuccess();
            count("rejected");
            throw new DependencyRejectedException(settings.peer(), status, response.body());
        }
        throw failure(lastReason, lastStatus, lastCause, attempts, attempts);
    }

    private HttpRequest build(PeerRequest request, TraceContext trace, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseText + request.path())).timeout(timeout)
                .header("Accept", "application/json");
        for (Map.Entry<String, String> h : request.headers().entrySet()) {
            if (!RESERVED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                builder.header(h.getKey(), h.getValue());
            }
        }
        builder.header("X-Correlation-Id", trace.correlationId()).header("traceparent", trace.traceparent());
        if (request.idempotencyKey() != null) {
            builder.header("Idempotency-Key", request.idempotencyKey());
        }
        HttpRequest.BodyPublisher body;
        if (request.body() != null) {
            builder.header("Content-Type", "application/json");
            body = HttpRequest.BodyPublishers.ofString(request.body());
        } else {
            body = HttpRequest.BodyPublishers.noBody();
        }
        return builder.method(request.method(), body).build();
    }

    private static Map<String, String> headersOf(HttpResponse<String> response) {
        Map<String, String> result = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                result.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return result;
    }

    private DependencyUnavailableException failure(String reason, int status, Throwable cause, int made, int attempts) {
        LOG.warn("Сосед {} недоступен: {}, сделано попыток {} из {}", settings.peer(), reason, made, attempts);
        return new DependencyUnavailableException(settings.peer(), reason, status, cause);
    }

    private void logAttempt(String reason, int status, int attempt, int attempts) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Сосед {}: попытка {} из {} не удалась: {}{}", settings.peer(), attempt, attempts, reason,
                    status > 0 ? " (" + status + ")" : "");
        }
    }

    private void count(String outcome) {
        metrics.counter("dgm_client_requests_total", "peer", settings.peer(), "outcome", outcome).increment();
    }
}
