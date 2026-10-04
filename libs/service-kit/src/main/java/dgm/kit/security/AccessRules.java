package dgm.kit.security;

import dgm.kit.problem.ProblemType;
import dgm.kit.route.RouteRule;
import java.util.Optional;
import java.util.Set;

/**
 * Грубая проверка доступа по правилу маршрута: роль, второй фактор, сессия по SMS (roles-permissions.md, разделы 2, 5 и 7).
 * Владельца объекта (условие U1) и статус объекта проверяет сам сервис: шлюз и каркас их не знают.
 */
public final class AccessRules {

    /** Роли, для которых обязателен второй фактор (roles-permissions.md, раздел 2): продавец и все сотрудники. */
    public static final Set<String> SECOND_FACTOR_ROLES = Set.of("seller", "moderator", "support-operator", "admin");

    private AccessRules() {
    }

    /** Причина отказа или пусто, если доступ разрешён. */
    public static Optional<Denial> check(AuthenticatedUser user, RouteRule rule) {
        if (!rule.roles().isEmpty() && rule.roles().stream().noneMatch(user::hasRole)) {
            return Optional.of(new Denial(ProblemType.FORBIDDEN, "Роль пользователя не допускает эту операцию."));
        }
        if (user.roles().stream().anyMatch(SECOND_FACTOR_ROLES::contains) && !user.secondFactorPassed()) {
            return Optional.of(new Denial(ProblemType.SECOND_FACTOR_REQUIRED, "Для этой роли нужна двухфакторная проверка."));
        }
        if (rule.smsSessionDenied() && user.smsSession()) {
            return Optional.of(new Denial(ProblemType.FULL_LOGIN_REQUIRED, "Действие недоступно для сессии по SMS."));
        }
        return Optional.empty();
    }

    /** Отказ: тип проблемы и объяснение случая. */
    public record Denial(ProblemType type, String detail) {
    }
}
