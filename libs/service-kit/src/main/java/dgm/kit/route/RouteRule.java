package dgm.kit.route;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Правило одного маршрута из контракта OpenAPI: какая область нужна токену, какие роли допущены, закрыта ли сессия по SMS и
 * какие сервисы могут вызывать маршрут изнутри.
 *
 * @param method      метод HTTP
 * @param path        шаблон пути, например {@code /api/v1/orders/{orderId}}
 * @param operationId идентификатор операции из OpenAPI
 * @param scopes      области токена, нужны все; пусто у публичных маршрутов, вебхуков и внутренних вызовов
 * @param roles       роли Keycloak, которым маршрут доступен ({@code x-allowed-roles}); пусто, если токен не нужен
 * @param smsSessionDenied маршрут закрыт для сессии по SMS ({@code x-sms-session: denied})
 * @param callers     сервисы, которым разрешён внутренний вызов ({@code x-allowed-callers}); не пусто у внутренних маршрутов
 */
public record RouteRule(String method, String path, String operationId, List<String> scopes, List<String> roles,
                        boolean smsSessionDenied, List<String> callers) {

    public RouteRule {
        scopes = List.copyOf(scopes);
        roles = List.copyOf(roles);
        callers = List.copyOf(callers);
    }

    /** Маршрут требует токен пользователя. */
    public boolean requiresToken() {
        return !scopes.isEmpty() || !roles.isEmpty();
    }

    /** Маршрут внутренний: вход по клиентскому сертификату сервиса. */
    public boolean internal() {
        return !callers.isEmpty();
    }

    /** Регулярное выражение пути: {@code {имя}} это один непустой сегмент. */
    Pattern pattern() {
        StringBuilder regex = new StringBuilder("^");
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            regex.append('/');
            if (segment.startsWith("{") && segment.endsWith("}")) {
                regex.append("[^/]+");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        if (regex.length() == 1) {
            regex.append('/');
        }
        return Pattern.compile(regex.append("$").toString());
    }

    /** Число постоянных сегментов: чем больше, тем точнее шаблон. */
    int literalSegments() {
        int count = 0;
        for (String segment : path.split("/", -1)) {
            if (!segment.isEmpty() && !(segment.startsWith("{") && segment.endsWith("}"))) {
                count++;
            }
        }
        return count;
    }
}
