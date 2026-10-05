package dgm.gateway;

import dgm.kit.problem.Problem;
import dgm.kit.problem.ProblemType;
import dgm.kit.route.PathGuard;
import dgm.kit.route.RouteRule;
import dgm.kit.security.AccessRules;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.trace.TraceContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Единственный фильтр шлюза со своей логикой (правило «тонкого шлюза», ADR-021): всё решается здесь, до маршрутизации Spring Cloud
 * Gateway, и запрос идёт дальше только после всех проверок.
 *
 * <ol>
 *   <li>сквозной идентификатор: {@code traceparent} и {@code X-Correlation-Id} входящего запроса или новые, в каждом ответе;
 *   <li>путь: недопустимые символы дают 400, маршрута нет в таблице (в том числе {@code /internal/**}) дают 404;
 *   <li>размер тела по {@code Content-Length}: 2 МБ, у вебхуков 64 КБ (413);
 *   <li>токен там, где он нужен: подпись, срок, издатель, адресат (401, ключи Keycloak недоступны: 503), затем области маршрута,
 *       второй фактор ролей продавца и сотрудников, запрет сессии по SMS (403). Роль и владельца ресурса проверяет сервис;
 *   <li>лимит частоты: пользователь из токена, иначе IP; при превышении 429 и {@code Retry-After}; если Redis не отвечает, запрос
 *       пропускается и считается в метрике (отказ открытым);
 *   <li>внешние заголовки с внутренним смыслом удаляются, токен идёт сервису без изменений.
 * </ol>
 */
public final class EntryFilter implements WebFilter, Ordered {

    /** Атрибут запроса: куда он идёт ({@link RouteTable.Target}), его читает предикат маршрутов. */
    public static final String TARGET = "dgm.gateway.target";

    /** Атрибут запроса: сквозной контекст ({@link TraceContext}), его читает обработчик ошибок, чтобы ответ нёс тот же идентификатор. */
    static final String TRACE = "dgm.gateway.trace";

    static final String TRACEPARENT = "traceparent";
    static final String CORRELATION_ID = "X-Correlation-Id";

    /** Заголовки, которые клиент присылать не вправе: личность и адрес источника выставляет шлюз, а не клиент. */
    private static final List<String> NOT_FROM_CLIENT = List.of("X-Seller-Id", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
            "X-Forwarded-Port", "X-Forwarded-Prefix", "Forwarded", "X-Real-IP");

    private static final Duration LOG_PERIOD = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(EntryFilter.class);

    /** Причина последнего отказа ограничителя (тип и сообщение без стека) для предупреждения в журнале. */
    private volatile String lastLimiterError = "нет (ответ Redis с признаком сбоя)";

    private final RouteTable routes;
    private final TokenVerifier tokens;
    private final RequestLimiter limiter;
    private final GatewayProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int managementPort;
    private final AtomicLong lastFailOpenLog = new AtomicLong(Long.MIN_VALUE);

    /**
     * @param managementPort порт управления (метрики и здоровье, mTLS) или не больше нуля, если отдельного порта нет: запросы к нему шлюз
     *                       не трогает, потому что фильтры основного контекста WebFlux действуют и на дочернем контексте управления
     */
    public EntryFilter(RouteTable routes, TokenVerifier tokens, RequestLimiter limiter, GatewayProperties properties, MeterRegistry meters,
            Clock clock, int managementPort) {
        this.routes = routes;
        this.tokens = tokens;
        this.limiter = limiter;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        this.managementPort = managementPort;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (isManagement(request)) {
            return chain.filter(exchange);
        }
        HttpHeaders headers = request.getHeaders();
        TraceContext trace = TraceContext.incoming(headers.getFirst(TRACEPARENT), headers.getFirst(CORRELATION_ID));
        stampResponse(exchange, trace);

        String path = request.getURI().getRawPath();
        if (!safe(path)) {
            return reject(exchange, trace, ProblemType.BAD_REQUEST, "Путь запроса содержит недопустимые символы.", Map.of(), "bad_request");
        }
        Optional<RouteTable.Target> found = routes.resolve(request.getMethod().name(), path);
        if (found.isEmpty()) {
            return reject(exchange, trace, ProblemType.NOT_FOUND, "Такого маршрута нет.", Map.of(), "not_found");
        }
        RouteTable.Target target = found.get();
        long limit = (target.kind() == RouteTable.Kind.WEBHOOK ? properties.getWebhookMaxBody() : properties.getMaxBody()).toBytes();
        if (headers.getContentLength() > limit) {
            return reject(exchange, trace, ProblemType.PAYLOAD_TOO_LARGE, "Тело запроса больше допустимого (" + limit + " байт).", Map.of(),
                    "payload_too_large");
        }
        exchange.getAttributes().put(TARGET, target);
        if (!target.requiresToken()) {
            return limited(exchange, chain, target, null, trace);
        }
        String token = bearer(headers.getFirst(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            return reject(exchange, trace, ProblemType.UNAUTHENTICATED, "Нужен токен доступа в заголовке Authorization.",
                    Map.of("WWW-Authenticate", "Bearer"), "unauthenticated");
        }
        return tokens.verify(token).flatMap(verdict -> {
            if (verdict instanceof TokenVerifier.Verdict.Rejected rejected) {
                return rejected.unavailable()
                        ? reject(exchange, trace, ProblemType.DEPENDENCY_UNAVAILABLE, "Проверка токена временно недоступна.",
                                Map.of("Retry-After", "5"), "token_unavailable")
                        : reject(exchange, trace, ProblemType.UNAUTHENTICATED, "Токен недействителен.",
                                Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""), "unauthenticated");
            }
            AuthenticatedUser user = ((TokenVerifier.Verdict.Accepted) verdict).user();
            Optional<AccessRules.Denial> denial = deny(user, target.rule());
            if (denial.isPresent()) {
                return reject(exchange, trace, denial.get().type(), denial.get().detail(), Map.of(), "forbidden");
            }
            return limited(exchange, chain, target, user, trace);
        });
    }

    private boolean isManagement(ServerHttpRequest request) {
        InetSocketAddress local = request.getLocalAddress();
        return managementPort > 0 && local != null && local.getPort() == managementPort;
    }

    /** Грубая проверка доступа: области маршрута, второй фактор, сессия по SMS. Роль и владельца ресурса проверяет сервис. */
    static Optional<AccessRules.Denial> deny(AuthenticatedUser user, RouteRule rule) {
        for (String scope : rule.scopes()) {
            if (!user.hasScope(scope)) {
                return Optional.of(new AccessRules.Denial(ProblemType.FORBIDDEN, "В токене нет области " + scope + "."));
            }
        }
        if (user.roles().stream().anyMatch(AccessRules.SECOND_FACTOR_ROLES::contains) && !user.secondFactorPassed()) {
            return Optional.of(new AccessRules.Denial(ProblemType.SECOND_FACTOR_REQUIRED, "Для этой роли нужна двухфакторная проверка."));
        }
        if (rule.smsSessionDenied() && user.smsSession()) {
            return Optional.of(new AccessRules.Denial(ProblemType.FULL_LOGIN_REQUIRED, "Действие недоступно для сессии по SMS."));
        }
        return Optional.empty();
    }

    private Mono<Void> limited(ServerWebExchange exchange, WebFilterChain chain, RouteTable.Target target, AuthenticatedUser user,
            TraceContext trace) {
        GatewayProperties.Limit limit = properties.getLimits().get(target.limit());
        if (limit == null) {
            return Mono.error(new IllegalStateException("Для группы лимита " + target.limit() + " нет настроек"));
        }
        String key = limit.perUser() && user != null ? "u:" + user.subject() : "ip:" + clientAddress(exchange.getRequest());
        return limiter.check(target.limit(), key)
                .timeout(properties.getLimiterTimeout())
                .onErrorResume(e -> {
                    lastLimiterError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    return Mono.just(RequestLimiter.Decision.unchecked());
                })
                .flatMap(decision -> {
                    if (decision.failOpen()) {
                        noteFailOpen(target.limit());
                    }
                    if (!decision.allowed()) {
                        return reject(exchange, trace, ProblemType.RATE_LIMITED, "Слишком много запросов, повторите позже.",
                                Map.of("Retry-After", Integer.toString(decision.retryAfterSeconds())), "rate_limited");
                    }
                    return forward(exchange, chain, trace);
                });
    }

    private void noteFailOpen(String group) {
        meters.counter("dgm.gateway.ratelimit.failopen", "group", group).increment();
        long now = clock.millis();
        long last = lastFailOpenLog.get();
        if ((last == Long.MIN_VALUE || now - last >= LOG_PERIOD.toMillis()) && lastFailOpenLog.compareAndSet(last, now)) {
            LOG.warn("Ограничитель частоты (Redis) не отвечает, запросы пропускаются без проверки лимита, группа {}, последняя ошибка: {}", group,
                    lastLimiterError);
        }
    }

    /**
     * Запрос сервису: присланные клиентом заголовки с внутренним смыслом убираются, сквозной контекст заменяется нашим. Адрес источника
     * и {@code X-Forwarded-*} затем выставляет Spring Cloud Gateway ({@code XForwardedHeadersFilter}, включён свойством
     * {@code trusted-proxies}): он видит уже очищенный запрос, поэтому клиентским значениям не верит никто.
     */
    private static Mono<Void> forward(ServerWebExchange exchange, WebFilterChain chain, TraceContext trace) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate().headers(h -> {
            for (String name : NOT_FROM_CLIENT) {
                h.remove(name);
            }
            h.set(TRACEPARENT, trace.traceparent());
            h.set(CORRELATION_ID, trace.correlationId());
        }).build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    private Mono<Void> reject(ServerWebExchange exchange, TraceContext trace, ProblemType type, String detail, Map<String, String> extra,
            String reason) {
        meters.counter("dgm.gateway.rejected", "reason", reason).increment();
        return writeProblem(exchange, trace.correlationId(), type, detail, extra);
    }

    /** Ответ-проблема RFC 9457 (тот же формат, что у сервисов). */
    static Mono<Void> writeProblem(ServerWebExchange exchange, String correlationId, ProblemType type, String detail, Map<String, String> extra) {
        ServerHttpResponse response = exchange.getResponse();
        byte[] body = new Problem(type, detail, exchange.getRequest().getURI().getRawPath(), correlationId, Map.of()).toJson()
                .getBytes(StandardCharsets.UTF_8);
        response.setStatusCode(HttpStatusCode.valueOf(type.status()));
        HttpHeaders out = response.getHeaders();
        out.set(HttpHeaders.CONTENT_TYPE, "application/problem+json");
        out.set(HttpHeaders.CACHE_CONTROL, "no-store");
        out.setContentLength(body.length);
        extra.forEach(out::set);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** Идентификатор запроса и заголовки безопасности в каждом ответе: ставятся перед отправкой, когда заголовки сервиса уже скопированы. */
    private static void stampResponse(ServerWebExchange exchange, TraceContext trace) {
        exchange.getAttributes().put(TRACE, trace);
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders h = exchange.getResponse().getHeaders();
            h.set(TRACEPARENT, trace.traceparent());
            h.set(CORRELATION_ID, trace.correlationId());
            setIfAbsent(h, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            setIfAbsent(h, "X-Content-Type-Options", "nosniff");
            setIfAbsent(h, "X-Frame-Options", "DENY");
            setIfAbsent(h, "Referrer-Policy", "no-referrer");
            return Mono.empty();
        });
    }

    private static void setIfAbsent(HttpHeaders headers, String name, String value) {
        if (headers.getFirst(name) == null) {
            headers.set(name, value);
        }
    }

    /** Путь без хитростей: для API строго (PathGuard каркаса), для остального без выхода из каталога и обратных косых. */
    static boolean safe(String path) {
        if (strict(path, "/api") || strict(path, "/internal") || strict(path, "/auth") || strict(path, "/actuator")) {
            return PathGuard.isSafe(path);
        }
        if (path.isEmpty() || path.charAt(0) != '/' || path.indexOf('\\') >= 0) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) < 0x20 || path.charAt(i) == 0x7f) {
                return false;
            }
        }
        return !(lower.contains("/../") || lower.endsWith("/..") || lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c"));
    }

    /**
     * Пути, по префиксу которых шлюз принимает решение о доступе, проверяются строго: любая запись символа иначе ({@code %6d} вместо
     * {@code m}, {@code ;параметр}, {@code //}) могла бы пройти мимо правила шлюза, а сервис за ним разобрал бы путь по-своему.
     */
    private static boolean strict(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    /** Токен из {@code Authorization: Bearer ...}; без заголовка, с другой схемой и пустой токен дают {@code null}. */
    static String bearer(String header) {
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String clientAddress(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
