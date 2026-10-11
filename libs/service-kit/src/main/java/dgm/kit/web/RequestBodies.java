package dgm.kit.web;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Чтение тела запроса целиком с пределом размера. Прочитанное тело запоминается обёрткой {@link CachedBodyRequest}. */
public final class RequestBodies {

    private RequestBodies() {
    }

    /** Размер по умолчанию: 2 МБ, как на шлюзе. */
    public static final long DEFAULT_LIMIT = 2L * 1024 * 1024;

    /**
     * Тело запроса: из обёртки, если оно уже прочитано, иначе читается из потока не больше {@code limit} байт.
     *
     * @throws ProblemException {@code payload-too-large} (413), если тело больше предела
     */
    public static byte[] read(HttpServletRequest request, long limit) throws IOException {
        if (request instanceof CachedBodyRequest cached) {
            return cached.body();
        }
        if (request.getContentLengthLong() > limit) {
            throw tooLarge(limit);
        }
        try (InputStream in = request.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(request.getContentLengthLong(), 512), limit));
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > limit) {
                    throw tooLarge(limit);
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Запрос с телом, прочитанным в память; если тело уже в обёртке, возвращается она же. */
    public static CachedBodyRequest cached(HttpServletRequest request, long limit) throws IOException {
        if (request instanceof CachedBodyRequest cached) {
            return cached;
        }
        return new CachedBodyRequest(request, read(request, limit));
    }

    public static ProblemException tooLarge(long limit) {
        return new ProblemException(ProblemType.PAYLOAD_TOO_LARGE, "Тело запроса больше допустимых " + describe(limit) + ".");
    }

    private static String describe(long bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return bytes / (1024 * 1024) + " МБ";
        }
        if (bytes >= 1024 && bytes % 1024 == 0) {
            return bytes / 1024 + " КБ";
        }
        return bytes + " байт";
    }
}
