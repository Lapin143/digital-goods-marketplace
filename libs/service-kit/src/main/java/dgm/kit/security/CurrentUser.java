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

    /**
     * Идентификатор профиля продавца из claim {@code seller_id} (Р-18). Нет claim или он не UUID: 403, потому что роль продавца есть,
     * а профиля в токене нет, например токен выдан до одобрения заявки; продавцу нужно войти заново.
     */
    public static UUID sellerId(AuthenticatedUser user) {
        try {
            return UUID.fromString(user.sellerId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ProblemException(ProblemType.FORBIDDEN, "В токене нет профиля продавца: войдите заново после одобрения заявки.");
        }
    }
}
