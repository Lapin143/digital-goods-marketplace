package dgm.platform.identity.domain;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Роли платформы (roles-permissions.md, раздел 2). Значения совпадают с ролями realm Keycloak и с ограничением
 * {@code ck_user_account_role}. Гость в базе не хранится.
 */
public final class Roles {

    public static final String BUYER = "buyer";
    public static final String SELLER = "seller";
    public static final String MODERATOR = "moderator";
    public static final String SUPPORT_OPERATOR = "support-operator";
    public static final String ADMIN = "admin";

    /** Все роли по возрастанию полномочий: при нескольких ролях у пользователя в Keycloak выбирается последняя из найденных. */
    public static final List<String> ALL = List.of(BUYER, SELLER, MODERATOR, SUPPORT_OPERATOR, ADMIN);

    private static final Set<String> STAFF = Set.of(MODERATOR, SUPPORT_OPERATOR, ADMIN);

    private Roles() {
    }

    public static boolean known(String role) {
        return ALL.contains(role);
    }

    /** Роль сотрудника: второй фактор обязателен (INV-37). */
    public static boolean staff(String role) {
        return STAFF.contains(role);
    }

    /**
     * Вход в кабинет роли требует второго фактора (INV-37): у всех ролей, кроме покупателя. Продавец в их числе, но сотрудником не
     * считается ({@link #staff}): роль продавца ставится только по событию, роли сотрудников только администратором.
     */
    public static boolean secondFactorRequired(String role) {
        return known(role) && !BUYER.equals(role);
    }

    /** Старшая из известных платформе ролей среди присланных; {@code buyer}, если известных нет. Лишние роли realm (default-roles-dgm и др.) игнорируются. */
    public static String best(Collection<String> roles) {
        String best = BUYER;
        for (String role : roles) {
            if (known(role) && ALL.indexOf(role) > ALL.indexOf(best)) {
                best = role;
            }
        }
        return best;
    }
}
