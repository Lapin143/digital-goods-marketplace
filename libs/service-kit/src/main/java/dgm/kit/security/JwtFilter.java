package dgm.kit.security;

import dgm.kit.problem.ProblemType;
import dgm.kit.problem.ProblemWriter;
import dgm.kit.route.PathGuard;
import dgm.kit.route.RoutePolicy;
import dgm.kit.route.RouteRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Фильтр токена (компонент {@code jwt-filter}, c4-components.md, раздел 2).
 *
 * <p>Порядок: путь проверяется {@link PathGuard}, затем ищется правило маршрута; маршрута нет в контракте, ответ 404 (всё, чего нет
 * в OpenAPI, закрыто). Публичные и внутренние маршруты проходят дальше (внутренние проверяет {@link CallerFilter}). Для остальных
 * нужен токен: подпись, срок, издатель и адресат проверяет декодер, области и роль проверяет фильтр по правилу маршрута. Владельца
 * объекта и статус объекта проверяет сам сервис.
 */
public final class JwtFilter extends OncePerRequestFilter {

    /** Атрибут запроса: {@link AuthenticatedUser}, есть только у маршрутов с токеном. */
    public static final String USER_ATTRIBUTE = "dgm.user";
    /** Атрибут запроса: {@link RouteRule} найденного маршрута. */
    public static final String ROUTE_ATTRIBUTE = "dgm.route";

    private static final Logger LOG = LoggerFactory.getLogger(JwtFilter.class);
    private static final String BEARER = "bearer";

    private final RoutePolicy policy;
    private final JwtDecoder decoder;

    public JwtFilter(RoutePolicy policy, JwtDecoder decoder) {
        this.policy = policy;
        this.decoder = decoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!PathGuard.isSafe(path)) {
            ProblemWriter.write(request, response, ProblemType.BAD_REQUEST, "Путь запроса содержит недопустимые символы.");
            return;
        }
        Optional<RouteRule> found = policy.match(request.getMethod(), path);
        if (found.isEmpty()) {
            ProblemWriter.write(request, response, ProblemType.NOT_FOUND, "Маршрут не найден.");
            return;
        }
        RouteRule rule = found.get();
        request.setAttribute(ROUTE_ATTRIBUTE, rule);
        if (rule.internal() || !rule.requiresToken()) {
            chain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request.getHeader("Authorization"));
        if (token == null) {
            ProblemWriter.write(request, response, ProblemType.UNAUTHENTICATED, "Нужен токен доступа в заголовке Authorization.");
            return;
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (BadJwtException e) {
            LOG.info("Токен отклонён: {}", e.getMessage());
            invalidToken(request, response);
            return;
        } catch (JwtException e) {
            // Ключи Keycloak недоступны: это отказ зависимости, а не ошибка клиента
            LOG.warn("Не удалось проверить токен: {}", e.getMessage());
            ProblemWriter.write(request, response, ProblemType.DEPENDENCY_UNAVAILABLE, "Проверка токена временно недоступна.");
            return;
        }

        AuthenticatedUser user = AuthenticatedUser.from(jwt);
        for (String scope : rule.scopes()) {
            if (!user.hasScope(scope)) {
                ProblemWriter.write(request, response, ProblemType.FORBIDDEN, "В токене нет нужной области доступа.");
                return;
            }
        }
        Optional<AccessRules.Denial> denial = AccessRules.check(user, rule);
        if (denial.isPresent()) {
            ProblemWriter.write(request, response, denial.get().type(), denial.get().detail());
            return;
        }
        request.setAttribute(USER_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    /** Токен из заголовка {@code Authorization: Bearer ...}; без заголовка, с другой схемой и с пустым значением {@code null}. */
    static String bearerToken(String header) {
        if (header == null) {
            return null;
        }
        int space = header.indexOf(' ');
        if (space <= 0 || !header.substring(0, space).toLowerCase(Locale.ROOT).equals(BEARER)) {
            return null;
        }
        String token = header.substring(space + 1).trim();
        return token.isEmpty() ? null : token;
    }

    private static void invalidToken(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemWriter.write(request, response,
                ProblemWriter.problem(request, ProblemType.UNAUTHENTICATED, "Токен недействителен.", java.util.Map.of()),
                java.util.Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\""));
    }
}
