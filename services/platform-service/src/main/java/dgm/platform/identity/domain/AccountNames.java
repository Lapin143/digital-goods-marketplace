package dgm.platform.identity.domain;

import java.util.Locale;
import java.util.UUID;

/**
 * Значения полей новой записи, которых нет в профиле Keycloak или которые в нём неполны (решения Р-7 и Р-30 руководства Ф4).
 * Функции чистые: сеть и база здесь не нужны.
 */
public final class AccountNames {

    public static final int NAME_MAX = 200;
    public static final int EMAIL_MAX = 254;
    private static final String FALLBACK_NAME = "Пользователь";

    private AccountNames() {
    }

    /**
     * Адрес в виде, который принимает {@code ck_user_account_email}: нижний регистр, без пробелов по краям, до 254 знаков, {@code @} не первым
     * знаком. Берётся адрес профиля, затем логин, если он похож на адрес (в realm логин равен адресу), иначе служебный
     * {@code user-<id>@invalid}: запись обязана появиться, иначе одна неполная учётная запись остановит сверку всех остальных.
     */
    public static String email(String profileEmail, String username, UUID id) {
        for (String candidate : new String[] {profileEmail, username}) {
            String normalized = normalize(candidate);
            if (valid(normalized)) {
                return normalized;
            }
        }
        return "user-" + id + "@invalid";
    }

    /** Имя профиля, а без него часть адреса до {@code @} (логин в этом realm это адрес, и показывать его целиком незачем). */
    public static String displayName(String fullName, String username, String email) {
        String name = fullName == null ? "" : fullName.strip();
        if (name.isEmpty()) {
            name = localPart(email != null && !email.isBlank() ? email : username);
        }
        if (name.isEmpty()) {
            return FALLBACK_NAME;
        }
        return name.codePointCount(0, name.length()) <= NAME_MAX ? name : name.substring(0, name.offsetByCodePoints(0, NAME_MAX));
    }

    private static String localPart(String address) {
        if (address == null) {
            return "";
        }
        String text = address.strip();
        int at = text.indexOf('@');
        return (at > 0 ? text.substring(0, at) : text).strip();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean valid(String email) {
        return email.length() >= 3 && email.length() <= EMAIL_MAX && email.indexOf('@') > 0;
    }
}
