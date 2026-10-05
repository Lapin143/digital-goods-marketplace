package dgm.kit.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Строгий ETag по содержимому ответа и условный запрос {@code If-None-Match} (conventions.md, раздел 8). */
public final class Etags {

    private Etags() {
    }

    /** ETag вида {@code "хеш"}: первые 128 бит SHA-256 тела в base64url. */
    public static String of(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            byte[] head = new byte[16];
            System.arraycopy(digest, 0, head, 0, head.length);
            return "\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(head) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    /** Значение {@code If-None-Match} подходит к ETag: {@code *} или один из перечисленных (слабое сравнение). */
    public static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        if (ifNoneMatch.trim().equals("*")) {
            return true;
        }
        String wanted = strip(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            if (strip(candidate).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String strip(String tag) {
        String t = tag.trim();
        return t.startsWith("W/") ? t.substring(2) : t;
    }
}
