package dgm.kit.log;

import java.util.regex.Pattern;

/**
 * Маскирование адресов электронной почты, телефонов и значений API-ключей в тексте, который уходит в журнал или в заголовок DLQ (conventions.md, 13.4).
 * Это страховка на случай ошибки: прикладной код не должен класть такие данные в тексты ошибок (правило 13.2).
 */
public final class Redaction {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{8,}\\d");
    /** Значение API-ключа продавца: {@code dgm_} и 40 знаков base64url (ADR-027, п. 6). Отпечаток из восьми знаков не секрет и под правило не попадает. */
    private static final Pattern API_KEY = Pattern.compile("dgm_[A-Za-z0-9_-]{40}");

    private Redaction() {
    }

    public static String mask(String text) {
        if (text == null) {
            return "";
        }
        String masked = API_KEY.matcher(text).replaceAll("dgm_***");
        masked = EMAIL.matcher(masked).replaceAll("***@***");
        return PHONE.matcher(masked).replaceAll("***");
    }

    /** Маскирует и обрезает текст до {@code max} символов. */
    public static String mask(String text, int max) {
        String masked = mask(text);
        return masked.length() <= max ? masked : masked.substring(0, max);
    }
}
