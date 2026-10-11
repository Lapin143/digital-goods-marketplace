package dgm.keycloak.smsotp.core;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Разбор номера, введённого покупателем. Принимаются российские номера в привычной записи: {@code +7 900 123-45-67},
 * {@code 8 (900) 123-45-67}, {@code 79001234567}, {@code 9001234567}. Результат всегда в виде E.164 ({@code +79001234567}), как требует
 * платформа ({@code ^\+7[0-9]{10}$}, ADR-010: только +7 в R1). Всё остальное отклоняется здесь же, до обращения к платформе.
 *
 * <p>Отказ по формату не зависит от того, есть ли у номера учётная запись, поэтому ответ нейтрален (ST-06).
 */
public final class PhoneInput {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-()]");
    private static final Pattern E164 = Pattern.compile("^\\+7[0-9]{10}$");
    private static final Pattern WITH_COUNTRY = Pattern.compile("^[78]([0-9]{10})$");
    private static final Pattern NATIONAL = Pattern.compile("^[0-9]{10}$");

    private PhoneInput() {
    }

    /**
     * Приводит ввод к виду {@code +7XXXXXXXXXX}.
     *
     * @param raw строка из формы; может быть {@code null}
     * @return номер в E.164 или пусто, если ввод не похож на российский номер
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = SEPARATORS.matcher(raw.strip()).replaceAll("");
        if (E164.matcher(cleaned).matches()) {
            return Optional.of(cleaned);
        }
        var withCountry = WITH_COUNTRY.matcher(cleaned);
        if (withCountry.matches()) {
            return Optional.of("+7" + withCountry.group(1));
        }
        if (NATIONAL.matcher(cleaned).matches()) {
            return Optional.of("+7" + cleaned);
        }
        return Optional.empty();
    }

    /** Код из SMS: ровно шесть цифр (пробелы по краям и внутри игнорируются). */
    public static Optional<String> normalizeCode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String cleaned = raw.replaceAll("\\s", "");
        return cleaned.matches("^[0-9]{6}$") ? Optional.of(cleaned) : Optional.empty();
    }
}
