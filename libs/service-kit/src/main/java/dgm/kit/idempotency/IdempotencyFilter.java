package dgm.kit.idempotency;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.problem.ProblemWriter;
import dgm.kit.route.RouteRule;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CallerFilter;
import dgm.kit.security.JwtFilter;
import dgm.kit.web.CachedBodyRequest;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Фильтр ключа идемпотентности (компонент {@code idempotency-guard}, ADR-006, уровень 1). Работает на маршрутах с пометкой
 * {@code idempotent} в правилах маршрутов, то есть на операциях OpenAPI с параметром {@code Idempotency-Key}.
 *
 * <p>Нет заголовка: 400 {@code idempotency-key-required}; не UUID версии 4: 400 {@code bad-request}. Дальше решает
 * {@link IdempotencyGuard}: новый ключ выполняет запрос, повтор получает сохранённый ответ с {@code Idempotency-Replayed: true},
 * тот же ключ с другим телом или адресом даёт 422, незавершённый запрос даёт 409 с {@code Retry-After}. Область ключа: пользователь по
 * токену, у внутренних вызовов CN сертификата сервиса.
 *
 * <p>Остальная цепочка (в том числе контроллер и сервис) выполняется внутри транзакции охранника: сервис открывает свою транзакцию
 * того же модуля, и она присоединяется к этой, поэтому ответ сохраняется вместе с изменением данных. Ответ уходит клиенту после фиксации.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";
    /** Заголовки первого ответа, которые повтор воспроизводит. */
    static final String[] REPLAYED_HEADERS = {"Location", "ETag"};

    private final Function<RouteRule, IdempotencyGuard> guards;
    private final long maxBody;

    /**
     * @param guards  охранник модуля, в чьей базе выполняет работу маршрут
     * @param maxBody предел размера тела, байт
     */
    public IdempotencyFilter(Function<RouteRule, IdempotencyGuard> guards, long maxBody) {
        this.guards = guards;
        this.maxBody = maxBody;
    }

    /** Обёртка для исключений цепочки, которые нельзя бросать из функции действия. */
    private static final class ChainFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ChainFailure(Exception cause) {
            super(cause);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(request.getAttribute(JwtFilter.ROUTE_ATTRIBUTE) instanceof RouteRule rule) || !rule.idempotent()) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(KEY_HEADER);
        if (header == null || header.isBlank()) {
            ProblemWriter.write(request, response, ProblemType.IDEMPOTENCY_KEY_REQUIRED, "Укажите заголовок Idempotency-Key.");
            return;
        }
        UUID key = IdempotencyRequest.parseKey(header);
        if (key == null) {
            ProblemWriter.write(request, response, ProblemType.BAD_REQUEST, "Idempotency-Key должен быть UUID версии 4.");
            return;
        }
        String scope = scope(request);
        CachedBodyRequest cached;
        try {
            cached = RequestBodies.cached(request, RequestBodies.limitFor(request, maxBody));
        } catch (ProblemException e) {
            write(request, response, e);
            return;
        }
        IdempotencyRequest idempotencyRequest = new IdempotencyRequest(scope, key, rule.method() + " " + rule.path(),
                IdempotencyRequest.sha256(cached.body()));
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);

        IdempotencyGuard.Outcome outcome;
        try {
            outcome = guards.apply(rule).execute(idempotencyRequest, () -> {
                try {
                    chain.doFilter(cached, wrapper);
                } catch (IOException | ServletException e) {
                    throw new ChainFailure(e);
                }
                return capture(wrapper);
            });
        } catch (ProblemException e) {
            write(request, response, e);
            return;
        } catch (ChainFailure e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) e.getCause();
        }
        if (outcome.replayed()) {
            replay(response, outcome.response());
        } else {
            wrapper.copyBodyToResponse();
        }
    }

    /** Область ключа: пользователь токена, иначе вызывающий сервис. Маршрут с ключом без того и другого это ошибка настройки. */
    private static String scope(HttpServletRequest request) {
        if (request.getAttribute(JwtFilter.USER_ATTRIBUTE) instanceof AuthenticatedUser user && user.subject() != null) {
            return user.subject();
        }
        if (request.getAttribute(CallerFilter.CALLER_ATTRIBUTE) instanceof String caller && !caller.isBlank()) {
            return caller;
        }
        throw new IllegalStateException("У маршрута с Idempotency-Key нет ни пользователя, ни вызывающего сервиса: ключу не к чему привязаться");
    }

    private static StoredResponse capture(ContentCachingResponseWrapper wrapper) {
        int status = wrapper.getStatus();
        byte[] content = wrapper.getContentAsByteArray();
        String body = content.length == 0 ? null : new String(content, StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : REPLAYED_HEADERS) {
            String value = wrapper.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        StoredResponse stored = new StoredResponse(status, body, headers);
        if (stored.successful() && body != null) {
            String type = wrapper.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("json")) {
                throw new IllegalStateException("Ответ операции с Idempotency-Key должен быть JSON, а не " + type);
            }
        }
        return stored;
    }

    private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        stored.headers().forEach(response::setHeader);
        response.setHeader(REPLAYED_HEADER, "true");
        if (stored.body() != null) {
            byte[] bytes = stored.body().getBytes(StandardCharsets.UTF_8);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
            response.getOutputStream().flush();
        }
    }

    private static void write(HttpServletRequest request, HttpServletResponse response, ProblemException e) throws IOException {
        ProblemWriter.write(request, response, ProblemWriter.problem(request, e.type(), e.getMessage(), e.extensions()), e.headers());
    }
}
