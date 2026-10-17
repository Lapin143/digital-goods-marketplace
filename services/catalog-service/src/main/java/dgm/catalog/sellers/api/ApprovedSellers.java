package dgm.catalog.sellers.api;

import java.util.UUID;

/**
 * Интерфейс модуля {@code sellers} для соседних модулей каталога: проверка условия U3 «продавец одобрен» (roles-permissions.md).
 * Соседний модуль не читает таблицы и репозитории профилей, он спрашивает только об этом.
 */
public interface ApprovedSellers {

    /** Профиль с таким идентификатором существует и находится в статусе «одобрен». */
    boolean isApproved(UUID sellerId);
}
