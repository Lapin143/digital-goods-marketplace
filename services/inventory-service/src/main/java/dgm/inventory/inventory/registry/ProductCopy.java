package dgm.inventory.inventory.registry;

import java.util.Set;
import java.util.UUID;

/**
 * Копия товара из событий каталога (реестр товаров, раздел 5 доменной модели): кто владелец, способ выдачи и статус. Нужна, чтобы
 * ответить «товар принадлежит продавцу» и «выдача из пула» без вызова каталога.
 *
 * @param productId     товар
 * @param sellerId      профиль продавца-владельца
 * @param issuanceMethod {@code key_pool} или {@code seller_api}
 * @param status        статус товара по SM-04 из последнего применённого события
 * @param sourceVersion версия товара из события (у копии, обновлённой событием без версии, прежняя)
 */
public record ProductCopy(UUID productId, UUID sellerId, String issuanceMethod, String status, int sourceVersion) {

    /** Статусы, в которых продавец догружает ключи (условие U4): блокированный и архивный товар пул не принимает. */
    private static final Set<String> UPLOAD_STATUSES = Set.of("draft", "on_moderation", "published", "rejected");

    public boolean ownedBy(UUID seller) {
        return sellerId.equals(seller);
    }

    /** Загрузка ключей разрешена: выдача из пула и статус не блокирован и не в архиве. */
    public boolean acceptsKeys() {
        return "key_pool".equals(issuanceMethod) && UPLOAD_STATUSES.contains(status);
    }
}
