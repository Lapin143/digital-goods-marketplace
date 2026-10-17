package dgm.kit.web;

import java.util.Locale;

/**
 * Пределы тела запроса, общие для шлюза и сервисов. Класс не зависит от сервлетов: шлюз работает на реактивном стеке и сервлетов не
 * знает.
 */
public final class BodyLimits {

    /**
     * Запас на служебные байты формы {@code multipart/form-data}: границы и заголовки частей. Предел 2 МБ относится к файлу, а не ко
     * всему телу запроса, поэтому файл ровно в 2 МБ проходит, а слишком большой файл отвергает сервис по размеру самого файла.
     */
    public static final long MULTIPART_ENVELOPE = 16L * 1024;

    private BodyLimits() {
    }

    /** Заголовок {@code Content-Type} это форма {@code multipart/form-data} (без учёта регистра и параметров). */
    public static boolean isMultipart(String contentType) {
        return contentType != null && contentType.trim().toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    /** Предел тела запроса с таким типом: заданный предел, а у формы multipart ещё и запас на служебные байты. */
    public static long limitFor(String contentType, long base) {
        return isMultipart(contentType) ? base + MULTIPART_ENVELOPE : base;
    }
}
