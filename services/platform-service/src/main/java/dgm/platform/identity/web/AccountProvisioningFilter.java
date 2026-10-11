package dgm.platform.identity.web;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.problem.ProblemWriter;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.platform.identity.service.AccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Создание учётной записи при первом обращении (ADR-025, пункт 1, решение Р-6). Стоит сразу после проверки токена: к этому моменту права по
 * правилу маршрута уже проверены, поэтому запись не создаётся для отказанных запросов (403, 404). Запросы без токена пользователя
 * (вызовы сервисов по mTLS, вебхуки, публичные маршруты) проходят дальше без изменений.
 */
public final class AccountProvisioningFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AccountProvisioningFilter.class);
    private static final int RETRY_AFTER_SECONDS = 5;

    private final AccountService accounts;

    public AccountProvisioningFilter(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(JwtFilter.USER_ATTRIBUTE) instanceof AuthenticatedUser user) {
            try {
                accounts.ensureAccount(user);
            } catch (ProblemException e) {
                ProblemWriter.write(request, response, ProblemWriter.problem(request, e.type(), e.getMessage(), e.extensions()), e.headers());
                return;
            } catch (DataAccessException e) {
                LOG.warn("Учётная запись не создана: база недоступна ({})", e.getClass().getSimpleName());
                ProblemWriter.write(request, response, ProblemWriter.problem(request, ProblemType.DEPENDENCY_UNAVAILABLE,
                        "Сервис временно недоступен, повторите запрос позже.", Map.of()),
                        Map.of("Retry-After", Integer.toString(RETRY_AFTER_SECONDS)));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
