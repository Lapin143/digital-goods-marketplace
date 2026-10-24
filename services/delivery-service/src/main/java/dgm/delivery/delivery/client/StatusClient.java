package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.ProviderLookup;
import java.util.UUID;

/** Запрос статуса письма у e-mail-провайдера (опрос, ADR-011): вторая линия защиты на случай потерянного вебхука. */
public interface StatusClient {

    /** Спрашивает статус письма. Ошибок не бросает: любой исход это {@link ProviderLookup}. */
    ProviderLookup statusOf(UUID messageId);
}
