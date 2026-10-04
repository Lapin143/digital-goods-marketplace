package dgm.kit.security;

import dgm.kit.problem.ProblemType;
import dgm.kit.problem.ProblemWriter;
import dgm.kit.route.RouteRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Фильтр вызывающего сервиса (компонент {@code caller-filter}, c4-components.md, раздел 2).
 *
 * <p>Внутренние маршруты ({@code x-internal}) доступны только сервисам из списка {@code x-allowed-callers}. Цепочку сертификата и срок
 * проверил TLS-слой (клиентский сертификат обязателен, центр сертификации проекта), здесь сопоставляется имя: CN клиентского
 * сертификата должно быть в списке маршрута. Токена пользователя у внутреннего вызова нет.
 */
public final class CallerFilter extends OncePerRequestFilter {

    /** Атрибут запроса: имя вызывающего сервиса (CN сертификата). */
    public static final String CALLER_ATTRIBUTE = "dgm.caller";
    /** Атрибут сервлета со списком сертификатов клиента, его заполняет контейнер при взаимной проверке TLS. */
    public static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private static final Logger LOG = LoggerFactory.getLogger(CallerFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object route = request.getAttribute(JwtFilter.ROUTE_ATTRIBUTE);
        if (!(route instanceof RouteRule rule) || !rule.internal()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<String> caller = callerName(request.getAttribute(CERTIFICATE_ATTRIBUTE));
        if (caller.isEmpty()) {
            ProblemWriter.write(request, response, ProblemType.UNAUTHENTICATED, "Нужен клиентский сертификат сервиса.");
            return;
        }
        if (!rule.callers().contains(caller.get())) {
            LOG.warn("Вызов {} {} отклонён: сервис {} не в списке допустимых", request.getMethod(), rule.path(), caller.get());
            ProblemWriter.write(request, response, ProblemType.FORBIDDEN, "Сервису запрещён этот внутренний вызов.");
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    /** Имя вызывающего: CN первого сертификата цепочки (сам клиент). Пусто, если сертификата нет, CN нет или их несколько. */
    static Optional<String> callerName(Object attribute) {
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0) {
            return Optional.empty();
        }
        return commonName(chain[0]);
    }

    static Optional<String> commonName(X509Certificate certificate) {
        try {
            List<Rdn> rdns = new LdapName(certificate.getSubjectX500Principal().getName()).getRdns();
            String found = null;
            for (Rdn rdn : rdns) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    if (found != null) {
                        return Optional.empty();
                    }
                    found = String.valueOf(rdn.getValue());
                }
            }
            return Optional.ofNullable(found).filter(s -> !s.isBlank());
        } catch (InvalidNameException e) {
            return Optional.empty();
        }
    }
}
