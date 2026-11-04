package dgm.catalog.sellers.apikey.domain;

import dgm.catalog.sellers.domain.SellerStatus;
import java.util.UUID;

/**
 * Что репозиторий знает о ключе, найденном по хешу, для внутренней проверки шлюза: идентификатор ключа, владелец, статус ключа и статус
 * профиля владельца (ADR-027, п. 3 и 5: статус профиля шлюз берёт из ответа проверки, а не из токена).
 */
public record KeyCheck(UUID apiKeyId, UUID sellerId, ApiKeyStatus keyStatus, SellerStatus sellerStatus) {
}
