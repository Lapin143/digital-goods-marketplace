package dgm.kit.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Идентификация запроса для охранника: кто, какой ключ, какой метод и путь, какое тело.
 *
 * @param scope       область ключа: идентификатор пользователя, у внутреннего вызова CN сертификата сервиса
 * @param key         значение заголовка {@code Idempotency-Key}
 * @param endpoint    метод и шаблон пути, например {@code POST /api/v1/orders}
 * @param requestHash SHA-256 тела запроса, 32 байта
 */
public record IdempotencyRequest(String scope, UUID key, String endpoint, byte[] requestHash) {

    public IdempotencyRequest {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Не задана область ключа идемпотентности");
        }
        if (key == null) {
            throw new IllegalArgumentException("Не задан ключ идемпотентности");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Не задан адрес запроса");
        }
        if (requestHash == null || requestHash.length != 32) {
            throw new IllegalArgumentException("Хэш тела запроса должен быть SHA-256 (32 байта)");
        }
        requestHash = requestHash.clone();
    }

    @Override
    public byte[] requestHash() {
        return requestHash.clone();
    }

    public static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен в этой JVM", e);
        }
    }

    /** Ключ идемпотентности из заголовка: UUID версии 4 в каноническом виде. Любое другое значение даёт {@code null}. */
    public static UUID parseKey(String header) {
        if (header == null || header.length() != 36) {
            return null;
        }
        try {
            UUID key = UUID.fromString(header.trim());
            return key.version() == 4 ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
