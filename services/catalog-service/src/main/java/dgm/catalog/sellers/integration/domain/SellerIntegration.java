package dgm.catalog.sellers.integration.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Подключение системы продавца (Р-187): куда платформа отправляет запрос ключа и уведомления. Секрета подписи в объекте нет: он
 * производный, считается из идентификатора продавца и {@code secretVersion} (ADR-028), и в таблице его тоже нет.
 *
 * @param sellerId      профиль продавца, одно подключение на продавца
 * @param endpointUrl   адрес, только {@code https}; защиту от SSRF проходит при сохранении и при каждом вызове
 * @param secretVersion версия секрета подписи, начинается с 1; прежний секрет перестаёт действовать сразу
 * @param status        {@code active} или {@code disabled}
 * @param version       версия записи для {@code ETag} и {@code If-Match}; растёт на единицу при каждом изменении (это делает триггер базы)
 * @param createdAt     когда система подключена
 * @param updatedAt     последнее изменение
 */
public record SellerIntegration(UUID sellerId, String endpointUrl, int secretVersion, IntegrationStatus status, long version, Instant createdAt,
                                Instant updatedAt) {

    public SellerIntegration {
        if (secretVersion < 1 || version < 1) {
            throw new IllegalArgumentException("Версии секрета и записи начинаются с 1");
        }
    }

    public boolean active() {
        return status == IntegrationStatus.ACTIVE;
    }

    /** Первое подключение: версия секрета 1, версия записи 1. */
    public static SellerIntegration connect(UUID sellerId, String endpointUrl, IntegrationStatus status, Instant now) {
        return new SellerIntegration(sellerId, endpointUrl, 1, status, 1, now, now);
    }
}
