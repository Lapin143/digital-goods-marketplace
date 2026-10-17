package dgm.kit.web;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Оптимистичная блокировка по версии записи (conventions.md, раздел 8): ответ несёт версию как строгий ETag вида {@code "7"}, правка
 * присылает её в {@code If-Match}, и сервис сравнивает с версией в базе под блокировкой строки. Две одновременные правки с одной
 * версией не затирают друг друга: вторая получает 412.
 */
public final class IfMatch {

    private static final Pattern STRONG_VERSION = Pattern.compile("^\"([0-9]{1,9})\"$");

    private IfMatch() {
    }

    /** ETag записи по версии: версия в кавычках. */
    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Версия из {@code If-Match}.
     *
     * @throws ProblemException 428, если заголовка нет; 400, если значение не строгий ETag вида {@code "7"}
     */
    public static long version(HttpServletRequest request) {
        String header = request.getHeader("If-Match");
        if (header == null || header.isBlank()) {
            throw new ProblemException(ProblemType.PRECONDITION_REQUIRED, "Для изменения нужен заголовок If-Match.");
        }
        Matcher matcher = STRONG_VERSION.matcher(header.strip());
        if (!matcher.matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "If-Match должен быть строгим ETag вида \"7\", как в заголовке ETag ответа.");
        }
        return Long.parseLong(matcher.group(1));
    }
}
