package dgm.kit.security;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Пользователь по проверенному токену: идентификатор, области, роль, способы входа и признак подтверждённого телефона.
 * Токен проверен целиком (подпись, срок, издатель, адресат) до создания этого объекта.
 *
 * @param subject       идентификатор пользователя (claim sub)
 * @param scopes        области токена (claim scope)
 * @param roles         роли realm (claim realm_access.roles); у пользователя одна роль
 * @param amr           способы входа: pwd, otp, vk, sms
 * @param phoneVerified номер телефона подтверждён (claim phone_verified)
 * @param sellerId      идентификатор профиля продавца (claim seller_id, Р-18): Keycloak кладёт его после одобрения заявки; у остальных
 *                      пользователей и в токенах, выданных до одобрения, его нет ({@code null})
 * @param email         адрес электронной почты (claim email, область токена {@code email}): попадает в снимок заказа (Р-137); в токене без
 *                      области или у пользователя без адреса его нет ({@code null}). Адрес персональные данные: в журнал и в тексты
 *                      ошибок не попадает ({@link #toString()} его скрывает)
 */
public record AuthenticatedUser(String subject, Set<String> scopes, Set<String> roles, List<String> amr, boolean phoneVerified,
                                String sellerId, String email) {

    public AuthenticatedUser {
        scopes = Set.copyOf(scopes);
        roles = Set.copyOf(roles);
        amr = List.copyOf(amr);
        email = email == null || email.isBlank() ? null : email;
    }

    /** Пользователь без профиля продавца и без адреса в токене. */
    public AuthenticatedUser(String subject, Set<String> scopes, Set<String> roles, List<String> amr, boolean phoneVerified) {
        this(subject, scopes, roles, amr, phoneVerified, null, null);
    }

    /** Пользователь без адреса в токене. */
    public AuthenticatedUser(String subject, Set<String> scopes, Set<String> roles, List<String> amr, boolean phoneVerified,
                             String sellerId) {
        this(subject, scopes, roles, amr, phoneVerified, sellerId, null);
    }

    public static AuthenticatedUser from(Jwt jwt) {
        Set<String> scopes = new HashSet<>();
        String scope = jwt.getClaimAsString("scope");
        if (scope != null && !scope.isBlank()) {
            scopes.addAll(List.of(scope.trim().split("\\s+")));
        }
        Set<String> roles = new HashSet<>();
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> list) {
            for (Object r : list) {
                roles.add(String.valueOf(r));
            }
        }
        List<String> amr = jwt.getClaimAsStringList("amr");
        String sellerId = jwt.getClaimAsString("seller_id");
        return new AuthenticatedUser(jwt.getSubject(), scopes, roles, amr == null ? List.of() : amr,
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("phone_verified")), sellerId == null || sellerId.isBlank() ? null : sellerId,
                jwt.getClaimAsString("email"));
    }

    /** Без адреса почты: он персональные данные и в журнал попадать не должен. */
    @Override
    public String toString() {
        return "AuthenticatedUser[subject=" + subject + ", roles=" + roles + ", amr=" + amr + ", phoneVerified=" + phoneVerified
                + ", sellerId=" + sellerId + ", email=" + (email == null ? "нет" : "скрыт") + "]";
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** В токене есть подтверждение второго фактора (INV-37). */
    public boolean secondFactorPassed() {
        return amr.contains("otp");
    }

    /** Сессия получена только входом по коду из SMS: действия, требующие полного входа, ей недоступны (FT-1.7). */
    public boolean smsSession() {
        return amr.contains("sms") && !amr.contains("pwd") && !amr.contains("vk");
    }
}
