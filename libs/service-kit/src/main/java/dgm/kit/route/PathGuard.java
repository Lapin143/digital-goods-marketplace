package dgm.kit.route;

/**
 * Проверка пути запроса до сопоставления с правилами. Контейнер сервлетов и Spring MVC разбирают путь по-своему (убирают
 * {@code ;параметры}, раскодируют {@code %xx}, схлопывают {@code //}), поэтому «хитрый» путь мог бы пройти мимо правила, а
 * контроллер всё равно сработал бы. Маршруты проекта состоят из латиницы, цифр, дефиса, подчёркивания и точки: любой другой
 * путь отклоняется целиком, а не толкуется.
 */
public final class PathGuard {

    private PathGuard() {
    }

    /** Путь допустим: начинается с {@code /}, без {@code //}, {@code ;}, {@code %}, {@code \}, управляющих и не-ASCII символов, без сегментов {@code .} и {@code ..}. */
    public static boolean isSafe(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '%' || c == ';' || c == '\\') {
                return false;
            }
        }
        if (path.contains("//")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
