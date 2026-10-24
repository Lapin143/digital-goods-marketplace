package dgm.delivery.delivery.domain;

import java.util.Objects;

/** Ответ провайдера на запрос статуса письма (опрос, 14.2 руководства среза 2). Ошибок не бросается: любой исход это значение. */
public sealed interface ProviderLookup {

    /** Провайдер назвал статус; причина есть у отказа. */
    record Known(ProviderStatus status, String reason) implements ProviderLookup {

        public Known {
            Objects.requireNonNull(status, "status");
        }
    }

    /** Провайдер такого письма не знает (404). */
    record Unknown() implements ProviderLookup {
    }

    /** Провайдер не ответил или ответил не по формату; следующий проход опроса спросит снова. Код по маске {@link AttemptResult#CODE}. */
    record Unavailable(String code) implements ProviderLookup {

        public Unavailable {
            Objects.requireNonNull(code, "code");
        }
    }
}
