package dgm.catalog.sellers.apikey.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Страница ключей продавца, новые выше. Курсор это пара «когда выпущен, идентификатор» последней строки страницы: порядок по индексу
 * {@code ix_api_key_seller_id} (created_at по убыванию, id по убыванию) однозначен даже при ключах, выпущенных в одну миллисекунду.
 */
public record ApiKeyPage(List<ApiKey> items, int limit, Optional<After> next) {

    /** Строго после этой пары в порядке «новые выше». */
    public record After(Instant createdAt, UUID id) {
    }
}
