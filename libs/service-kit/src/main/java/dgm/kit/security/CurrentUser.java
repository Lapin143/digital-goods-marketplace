package dgm.kit.security;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** Пользователь текущего запроса, проверенный {@link JwtFilter}. Для маршрутов без токена пользователя нет. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** Проверенный пользователь; нет атрибута (маршрут без токена или фильтр не отработал): 401. */
    public static AuthenticatedUser of(HttpServletRequest request) {
        if (request.getAttribute(JwtFilter.USER_ATTRIBUTE) instanceof AuthenticatedUser user) {
            return user;
        }
        throw new ProblemException(ProblemType.UNAUTHENTICATED, "Нужен токен доступа.");
    }

    /** Идентификатор пользователя: {@code sub} токена Keycloak это UUID версии 4. */
    public static UUID id(AuthenticatedUser user) {
        try {
            return UUID.fromString(user.subject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ProblemException(ProblemType.UNAUTHENTICATED, "Идентификатор пользователя в токене не распознан.");
        }
    }
}
