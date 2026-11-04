package dgm.gateway;

import dgm.kit.problem.Problem;
import dgm.kit.problem.ProblemType;
import dgm.kit.route.PathGuard;
import dgm.kit.route.RouteRule;
import dgm.kit.security.AccessRules;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.trace.TraceContext;
import dgm.kit.web.BodyLimits;
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
 *   <li>личность там, где она нужна. Токен: подпись, срок, издатель, адресат (401, ключи Keycloak недоступны: 503), затем области
 *       маршрута, второй фактор ролей продавца и сотрудников, запрет сессии по SMS (403). API-ключ продавца в {@code X-Api-Key}
 *       (маршруты с {@code x-seller-api}, ADR-027): формат, память в Redis, {@code checkApiKey} у каталога, профиль продавца
 *       одобрен (401, 403, каталог недоступен: 503). Токен и ключ вместе дают 400. Роль и владельца ресурса проверяет сервис;
 *   <li>лимит частоты: у вызова по токену пользователь из токена, у вызова по ключу ключ или продавец (по группе), у остальных IP;
 *       при превышении 429 и {@code Retry-After}; если Redis не отвечает, запрос пропускается и считается в метрике (отказ открытым);
 *   <li>внешние заголовки с внутренним смыслом удаляются, в том числе {@code X-Seller-Id}, {@code X-Auth-Kind} и {@code X-Api-Key}:
 *       личность по ключу сервису передаёт только шлюз, после проверки. Токен идёт сервису без изменений.
 * </ol>
 */
public final class EntryFilter implements WebFilter, Ordered {

    /** Атрибут запроса: куда он идёт ({@link RouteTable.Target}), его читает предикат маршрутов. */
    public static final String TARGET = "dgm.gateway.target";

    /** Атрибут запроса: сквозной контекст ({@link TraceContext}), его читает обработчик ошибок, чтобы ответ нёс тот же идентификатор. */
    static final String TRACE = "dgm.gateway.trace";

    static final String TRACEPARENT = "traceparent";
    static final String CORRELATION_ID = "X-Correlation-Id";

    /** Заголовок с API-ключом продавца (схема {@code sellerApiKey} контракта). */
    static final String API_KEY_HEADER = "X-Api-Key";

    /**
     * Личность по ключу, которую шлюз передаёт сервису (ADR-027, п. 4): те же имена и значение, что читает {@code GatewayIdentity}
     * каркаса. Класс каркаса здесь не подключается: он написан под сервлеты, а в сборке шлюза Servlet API нет. Совпадение имён держат два
     * теста: шлюза ({@code EntryFilterApiKeyTest}) и каркаса ({@code GatewayIdentityTest}), каждый закрепляет те же литералы со своей стороны.
     */
    static final String SELLER_ID_HEADER = "X-Seller-Id";
    static final String AUTH_KIND_HEADER = "X-Auth-Kind";
    static final String AUTH_KIND_API_KEY = "api-key";

    /**
     * Заголовки, которые клиент присылать не вправе: личность и адрес источника выставляет шлюз, а не клиент. Значение ключа сервис не
     * получает вовсе: ему достаточно личности, которую шлюз передаёт после проверки.
     */
    private static final List<String> NOT_FROM_CLIENT = List.of(SELLER_ID_HEADER, AUTH_KIND_HEADER, API_KEY_HEADER, "X-Forwarded-For",
            "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Prefix", "Forwarded", "X-Real-IP");

    private static final Duration LOG_PERIOD = Duration.ofSeconds(30);
    private static final Logger LOG = LoggerFactory.getLogger(EntryFilter.class);

    /** Журнал обращений: то же имя, что у сервисов (dgm.access), чтобы запросы всех контейнеров искались одним фильтром в Loki. */
    private static final Logger ACCESS = LoggerFactory.getLogger("dgm.access");

    private static final int MAX_LOGGED_PATH = 200;

    /** Причина последнего отказа ограничителя (тип и сообщение без стека) для предупреждения в журнале. */
    private volatile String lastLimiterError = "нет (ответ Redis с признаком сбоя)";

    private final RouteTable routes;
    private final TokenVerifier tokens;
    private final ApiKeyVerifier keys;
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
    public EntryFilter(RouteTable routes, TokenVerifier tokens, ApiKeyVerifier keys, RequestLimiter limiter, GatewayProperties properties,
            MeterRegistry meters, Clock clock, int managementPort) {
        this.routes = routes;
        this.tokens = tokens;
        this.keys = keys;
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
        long started = clock.millis();
        return route(exchange, chain, trace).doFinally(signal -> logAccess(exchange, trace, started));
    }

    /** Строка журнала обращений: метод, путь без строки запроса, статус ответа, время в мс. Сквозной идентификатор печатается в поле. */
    private void logAccess(ServerWebExchange exchange, TraceContext trace, long started) {
        if (!ACCESS.isInfoEnabled()) {
            return;
        }
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        String path = exchange.getRequest().getURI().getRawPath();
        ACCESS.info("{} {} {} {} мс correlationId={}", exchange.getRequest().getMethod().name(),
                path.length() > MAX_LOGGED_PATH ? path.substring(0, MAX_LOGGED_PATH) : path, status == null ? 200 : status.value(),
                clock.millis() - started, trace.correlationId());
    }

    private Mono<Void> route(ServerWebExchange exchange, WebFilterChain chain, TraceContext trace) {
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders headers = request.getHeaders();
        String path = request.getURI().getRawPath();
        if (!safe(path)) {
            return reject(exchange, trace, ProblemType.BAD_REQUEST, "Путь запроса содержит недопустимые символы.", Map.of(), "bad_request");
        }
        Optional<RouteTable.Target> found = routes.resolve(request.getMethod().name(), path);
        if (found.isEmpty()) {
            return reject(exchange, trace, ProblemType.NOT_FOUND, "Такого маршрута нет.", Map.of(), "not_found");
        }
        RouteTable.Target target = found.get();
        long base = (target.kind() == RouteTable.Kind.WEBHOOK ? properties.getWebhookMaxBody() : properties.getMaxBody()).toBytes();
        // Предел относится к файлу: у формы multipart вокруг файла есть границы и заголовки, их размер сверх предела не считается
        long limit = target.kind() != RouteTable.Kind.WEBHOOK ? BodyLimits.limitFor(headers.getFirst(HttpHeaders.CONTENT_TYPE), base) : base;
        if (headers.getContentLength() > limit) {
            return reject(exchange, trace, ProblemType.PAYLOAD_TOO_LARGE, "Тело запроса больше допустимого (" + base + " байт).", Map.of(),
                    "payload_too_large");
        }
        exchange.getAttributes().put(TARGET, target);
        if (!target.requiresAuth()) {
            return limited(exchange, chain, target, Identity.ANONYMOUS, trace);
        }
        List<String> keyValues = headers.get(API_KEY_HEADER);
        boolean hasKey = keyValues != null && !keyValues.isEmpty();
        if (hasKey && (keyValues.size() > 1 || headers.getFirst(HttpHeaders.AUTHORIZATION) != null)) {
            // Две личности в одном запросе: что из них верно, шлюз не угадывает (в сервисе та же проверка, но отказ даёт 401)
            return reject(exchange, trace, ProblemType.BAD_REQUEST, "Передайте токен или API-ключ, но не оба сразу.", Map.of(),
                    "ambiguous_identity");
        }
        if (target.acceptsKey() && hasKey) {
            return byKey(exchange, chain, target, keyValues.get(0), trace);
        }
        if (!target.acceptsToken()) {
            return reject(exchange, trace, ProblemType.UNAUTHENTICATED, "Нужен API-ключ продавца в заголовке " + API_KEY_HEADER + ".", Map.of(),
                    "unauthenticated");
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
            return limited(exchange, chain, target, Identity.ofUser(user), trace);
        });
    }

    /**
     * Вход по API-ключу. Области токена здесь не нужны: ключу достаточно того, что маршрут помечен {@code x-seller-api} (сервис проверяет
     * это же и роль). Ответ на неизвестный, отозванный и испорченный ключ один (401 без подсказки), статус профиля это отдельный
     * отказ (403), потому что держатель действующего ключа вправе знать, что дело в профиле.
     */
    private Mono<Void> byKey(ServerWebExchange exchange, WebFilterChain chain, RouteTable.Target target, String value, TraceContext trace) {
        return keys.verify(value, trace).flatMap(verdict -> switch (verdict) {
            case ApiKeyVerifier.Verdict.Accepted accepted -> limited(exchange, chain, target, Identity.ofKey(accepted), trace);
            case ApiKeyVerifier.Verdict.Rejected rejected -> rejected.reason() == ApiKeyVerifier.Reason.SELLER_NOT_APPROVED
                    ? reject(exchange, trace, ProblemType.FORBIDDEN, "Профиль продавца не одобрен, работа по API-ключу недоступна.", Map.of(),
                            "seller_not_approved")
                    : reject(exchange, trace, ProblemType.UNAUTHENTICATED, "API-ключ недействителен.", Map.of(), "unauthenticated");
            case ApiKeyVerifier.Verdict.Unavailable unavailable -> reject(exchange, trace, ProblemType.DEPENDENCY_UNAVAILABLE,
                    "Проверка API-ключа временно недоступна.", Map.of("Retry-After", "5"), "api_key_unavailable");
        });
    }

    /** Кто вызывает: пользователь по токену, ключ продавца или никто (открытые маршруты). */
    private record Identity(AuthenticatedUser user, ApiKeyVerifier.Verdict.Accepted key) {

        static final Identity ANONYMOUS = new Identity(null, null);

        static Identity ofUser(AuthenticatedUser user) {
            return new Identity(user, null);
        }

        static Identity ofKey(ApiKeyVerifier.Verdict.Accepted key) {
            return new Identity(null, key);
        }

        /** Профиль продавца: claim {@code seller_id} токена или продавец ключа; {@code null}, если личности нет или профиля у неё нет. */
        String sellerId() {
            return key != null ? key.sellerId() : user != null ? user.sellerId() : null;
        }
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

    private Mono<Void> limited(ServerWebExchange exchange, WebFilterChain chain, RouteTable.Target target, Identity identity,
            TraceContext trace) {
        // Вызов по ключу считается в своей группе (seller.api, а у загрузки пулов в общей с кабинетом seller-upload, Р-188)
        String group = identity.key() != null ? target.keyLimit() : target.limit();
        GatewayProperties.Limit limit = properties.limit(group);
        if (limit == null) {
            return Mono.error(new IllegalStateException("Для группы лимита " + group + " нет настроек"));
        }
        String key = counterKey(limit, identity, exchange.getRequest());
        return limiter.check(group, key)
                .timeout(properties.getLimiterTimeout())
                .onErrorResume(e -> {
                    lastLimiterError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    return Mono.just(RequestLimiter.Decision.unchecked());
                })
                .flatMap(decision -> {
                    if (decision.failOpen()) {
                        noteFailOpen(group);
                    }
                    if (!decision.allowed()) {
                        return reject(exchange, trace, ProblemType.RATE_LIMITED, "Слишком много запросов, повторите позже.",
                                Map.of("Retry-After", Integer.toString(decision.retryAfterSeconds())), "rate_limited");
                    }
                    return forward(exchange, chain, trace, identity.key());
                });
    }

    /**
     * Ключ счётчика по настройке группы. Префиксы разные, чтобы значения разных видов не совпали: {@code ip:}, {@code u:} (пользователь
     * токена), {@code k:} (ключ), {@code s:} (профиль продавца, общий для токена и ключа). Нет нужной личности: счёт идёт по IP.
     */
    private static String counterKey(GatewayProperties.Limit limit, Identity identity, ServerHttpRequest request) {
        String subject = identity.user() != null ? identity.user().subject() : null;
        return switch (limit.getKey()) {
            case "api-key" -> identity.key() != null ? "k:" + identity.key().apiKeyId() : fallback(subject, request);
            case "seller" -> identity.sellerId() != null ? "s:" + identity.sellerId() : fallback(subject, request);
            case "user" -> fallback(subject, request);
            default -> "ip:" + clientAddress(request);
        };
    }

    private static String fallback(String subject, ServerHttpRequest request) {
        return subject != null ? "u:" + subject : "ip:" + clientAddress(request);
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
    private static Mono<Void> forward(ServerWebExchange exchange, WebFilterChain chain, TraceContext trace,
            ApiKeyVerifier.Verdict.Accepted apiKey) {
        boolean toStorage = exchange.getAttributes().get(TARGET) instanceof RouteTable.Target target && "object-storage".equals(target.service());
        ServerHttpRequest sanitized = exchange.getRequest().mutate().headers(h -> {
            for (String name : NOT_FROM_CLIENT) {
                h.remove(name);
            }
            if (toStorage) {
                // Хранилищу нужна только подпись в адресе. Токен и куки пользователя ему не принадлежат, а заголовок Authorization
                // вместе с подписью в адресе хранилище отвергает как два способа входа сразу
                h.remove(HttpHeaders.AUTHORIZATION);
                h.remove(HttpHeaders.COOKIE);
            }
            if (apiKey != null) {
                // Личность по проверенному ключу: сервис верит этим заголовкам только от шлюза (сертификат api-gateway, Р-186)
                h.set(SELLER_ID_HEADER, apiKey.sellerId());
                h.set(AUTH_KIND_HEADER, AUTH_KIND_API_KEY);
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
            if (exchange.getAttributes().get(TARGET) instanceof RouteTable.Target target && "object-storage".equals(target.service())) {
                downloadHeaders(h);
            }
            return Mono.empty();
        });
    }

    /**
     * Документ продавца это чужой файл, и открывать его в браузере нельзя (T-36, Р-23): только скачивание, без исполнения содержимого
     * и без встраивания в страницы. Заголовки ставятся всегда, а не «если нет»: значение хранилища им не равнозначно.
     */
    private static void downloadHeaders(HttpHeaders h) {
        h.set(HttpHeaders.CONTENT_DISPOSITION, "attachment");
        h.set("Content-Security-Policy", "default-src 'none'; sandbox");
        h.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
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
