package dgm.kit.log;

import java.util.regex.Pattern;

/**
 * Маскирование адресов электронной почты и телефонов в тексте, который уходит в журнал или в заголовок DLQ (conventions.md, 13.4).
 * Это страховка на случай ошибки: прикладной код не должен класть такие данные в тексты ошибок (правило 13.2).
 */
public final class Redaction {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{8,}\\d");

    private Redaction() {
    }

    public static String mask(String text) {
        if (text == null) {
            return "";
        }
        String masked = EMAIL.matcher(text).replaceAll("***@***");
        return PHONE.matcher(masked).replaceAll("***");
    }

    /** Маскирует и обрезает текст до {@code max} символов. */
    public static String mask(String text, int max) {
        String masked = mask(text);
        return masked.length() <= max ? masked : masked.substring(0, max);
    }
}
