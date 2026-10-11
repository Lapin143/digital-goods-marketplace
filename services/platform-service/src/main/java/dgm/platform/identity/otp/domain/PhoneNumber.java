package dgm.platform.identity.otp.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Номер телефона в формате E.164, в R1 только российские ({@code +7} и десять цифр). Номер персональные данные: {@link #toString()}
 * отдаёт только маску, поэтому в журнал и сообщения об ошибках он попасть не может.
 *
 * @param value номер целиком, например {@code +79001234567}
 */
public record PhoneNumber(String value) {

    private static final Pattern RU = Pattern.compile("^\\+7[0-9]{10}$");

    public PhoneNumber {
        if (value == null || !RU.matcher(value).matches()) {
            throw new IllegalArgumentException("Номер не в формате +7 и десяти цифр");
        }
    }

    /** Разбирает номер; пусто, если строка не подходит (другой код страны, буквы, пробелы). */
    public static Optional<PhoneNumber> parse(String text) {
        return text != null && RU.matcher(text).matches() ? Optional.of(new PhoneNumber(text)) : Optional.empty();
    }

    /** Номер с маской для ответа и журнала: {@code +7900***4567}. */
    public String masked() {
        return value.substring(0, 5) + "***" + value.substring(8);
    }

    @Override
    public String toString() {
        return masked();
    }
}
