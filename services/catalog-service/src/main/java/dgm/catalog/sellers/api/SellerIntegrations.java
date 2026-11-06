package dgm.catalog.sellers.api;

import java.util.UUID;

/**
 * Интерфейс модуля {@code sellers} для модуля {@code catalog}: действует ли подключение системы продавца (R2, Р-187). Товар с выдачей по API
 * публикуется и продаётся, только пока платформа может запросить у продавца ключ; соседний модуль не читает таблицы подключений, он
 * спрашивает только об этом.
 */
public interface SellerIntegrations {

    /** Подключение есть и действует (статус {@code active}), а профиль продавца одобрен. */
    boolean hasActive(UUID sellerId);
}
