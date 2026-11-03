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

    /** Способ входа «API-ключ продавца»: в {@code amr} его нет у токенов Keycloak, его ставит только {@link #forApiKey(String)}. */
    public static final String AMR_API_KEY = "api-key";

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

    /**
     * Личность по API-ключу продавца (ADR-027): шлюз проверил ключ и передал идентификатор профиля заголовком {@code X-Seller-Id}.
     * Субъект это сам профиль продавца, роль {@code seller}, область {@code seller.api}, способ входа {@code api-key}. Второго фактора и
     * подтверждённого телефона у ключа нет: ключ не пользователь, права продавца на нём ограничены списком операций {@code x-seller-api}.
     */
    public static AuthenticatedUser forApiKey(String sellerId) {
        return new AuthenticatedUser(sellerId, Set.of("seller.api"), Set.of("seller"), List.of(AMR_API_KEY), false, sellerId, null);
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
        List<String> claimed = jwt.getClaimAsStringList("amr");
        // Способ входа «api-key» ставит только шлюзовая личность: в токене Keycloak он не значит ничего и второй фактор не отменяет
        List<String> amr = claimed == null ? List.of() : claimed.stream().filter(a -> !AMR_API_KEY.equals(a)).toList();
        String sellerId = jwt.getClaimAsString("seller_id");
        return new AuthenticatedUser(jwt.getSubject(), scopes, roles, amr,
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

    /** Личность получена по API-ключу, а не по токену Keycloak. */
    public boolean apiKey() {
        return amr.contains(AMR_API_KEY);
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
