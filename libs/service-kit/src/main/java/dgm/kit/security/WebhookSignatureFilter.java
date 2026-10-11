package dgm.kit.security;

import dgm.kit.crypto.WebhookSignature;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.problem.ProblemWriter;
import dgm.kit.route.RouteRule;
import dgm.kit.secret.SecretValue;
import dgm.kit.web.CachedBodyRequest;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Фильтр подписи вебхука (компонент {@code webhook-signature}, ADR-015). Работает на маршрутах с пометкой {@code webhookSecret}:
 * читает тело целиком (в пределах {@code limit}), проверяет подпись, затем свежесть метки времени и передаёт дальше запрос с уже
 * прочитанным телом. Подпись неверна: 401 {@code signature-invalid}; метка вне окна 5 минут: 401 {@code webhook-expired}. Значение секрета
 * и подпись в журнал не попадают.
 */
public final class WebhookSignatureFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookSignatureFilter.class);

    private final WebhookSecrets secrets;
    private final Clock clock;
    private final long limit;

    public WebhookSignatureFilter(WebhookSecrets secrets, Clock clock, long limit) {
        this.secrets = secrets;
        this.clock = clock;
        this.limit = limit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(request.getAttribute(JwtFilter.ROUTE_ATTRIBUTE) instanceof RouteRule rule) || !rule.webhook()) {
            chain.doFilter(request, response);
            return;
        }
        List<SecretValue> keys = secrets.forName(rule.webhookSecret());
        if (keys.isEmpty()) {
            LOG.error("Для вебхука {} не настроен секрет подписи", rule.operationId());
            ProblemWriter.write(request, response, ProblemType.INTERNAL_ERROR, "Приём вебхука не настроен.");
            return;
        }
        CachedBodyRequest cached;
        try {
            cached = RequestBodies.cached(request, limit);
        } catch (ProblemException e) {
            ProblemWriter.write(request, response, ProblemWriter.problem(request, e.type(), e.getMessage(), e.extensions()), e.headers());
            return;
        }
        WebhookSignature.Result result = WebhookSignature.verify(keys, request.getHeader(WebhookSignature.TIMESTAMP_HEADER),
                request.getHeader(WebhookSignature.SIGNATURE_HEADER), cached.body(), clock.instant());
        switch (result) {
            case VALID -> chain.doFilter(cached, response);
            case EXPIRED -> {
                LOG.warn("Вебхук {} отклонён: метка времени вне допустимого окна", rule.operationId());
                ProblemWriter.write(request, response, ProblemType.WEBHOOK_EXPIRED, "Метка времени уведомления вне допустимого окна.");
            }
            case SIGNATURE_INVALID -> {
                LOG.warn("Вебхук {} отклонён: подпись неверна", rule.operationId());
                ProblemWriter.write(request, response, ProblemType.SIGNATURE_INVALID, "Подпись уведомления неверна.");
            }
        }
    }
}
