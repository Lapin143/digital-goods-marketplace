package dgm.catalog.sellers.apikey.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * API-ключ продавца без значения (Р-185): идентификатор, имя, отпечаток, статус и даты. Значения ключа в объекте нет и быть не может:
 * платформа хранит только SHA-256 (INV-41), а хеш нужен лишь репозиторию при поиске и в объект не попадает.
 *
 * @param id          идентификатор ключа (UUID версии 7): по нему шлюз считает лимит и ключ называется в журналах
 * @param sellerId    профиль продавца-владельца
 * @param name        имя от 1 до 60 знаков, по нему продавец отличает ключи разных систем
 * @param fingerprint первые восемь знаков ключа после {@code dgm_}, не секрет
 * @param status      статус
 * @param createdAt   когда выпущен
 * @param revokedAt   когда отозван; есть ровно у отозванного (ограничение {@code ck_api_key_revoked_state})
 */
public record ApiKey(UUID id, UUID sellerId, String name, String fingerprint, ApiKeyStatus status, Instant createdAt, Instant revokedAt) {

    public ApiKey {
        if ((status == ApiKeyStatus.REVOKED) != (revokedAt != null)) {
            throw new IllegalArgumentException("Дата отзыва есть ровно у отозванного ключа");
        }
    }

    /** Новый ключ в статусе «действует». */
    public static ApiKey issue(UUID id, UUID sellerId, String name, String fingerprint, Instant now) {
        return new ApiKey(id, sellerId, name, fingerprint, ApiKeyStatus.ACTIVE, now, null);
    }

    public boolean active() {
        return status == ApiKeyStatus.ACTIVE;
    }

    /** Отзыв необратим. Отозванный ключ остаётся прежним, в том числе с прежней датой отзыва: повтор безвреден. */
    public ApiKey revoke(Instant now) {
        return active() ? new ApiKey(id, sellerId, name, fingerprint, ApiKeyStatus.REVOKED, createdAt, now) : this;
    }
}
