package dgm.catalog.sellers.integration;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.integration.domain.IntegrationStatus;
import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.catalog.sellers.integration.repository.IntegrationRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий подключений в памяти для модульных тестов. Повторяет то, на что полагается сервис и что в базе держат ограничения и триггер:
 * одно подключение на продавца ({@code pk_seller_integration}), адрес только {@code https} без пробелов и не длиннее 2048 знаков
 * ({@code ck_seller_integration_endpoint_url}), версия секрета не уменьшается, а версию записи и время изменения при каждом изменении ставит
 * триггер ({@code trg_seller_integration_touch}), и изменение строки допустимо только под блокировкой профиля продавца.
 */
public final class FakeIntegrations implements IntegrationRepository {

    private final FakeSellers sellers;
    private final Clock clock;
    private final Map<UUID, SellerIntegration> rows = new LinkedHashMap<>();
    private int updates;

    public FakeIntegrations(FakeSellers sellers, Clock clock) {
        this.sellers = sellers;
        this.clock = clock;
    }

    @Override
    public Optional<SellerIntegration> find(UUID sellerId) {
        return Optional.ofNullable(rows.get(sellerId));
    }

    @Override
    public void insert(SellerIntegration integration) {
        requireLocked(integration.sellerId());
        if (rows.containsKey(integration.sellerId())) {
            throw new IllegalStateException("pk_seller_integration: подключение продавца уже есть");
        }
        requireUrl(integration.endpointUrl());
        rows.put(integration.sellerId(), integration);
    }

    @Override
    public SellerIntegration update(UUID sellerId, String endpointUrl, IntegrationStatus status) {
        SellerIntegration current = existing(sellerId);
        requireUrl(endpointUrl);
        return touch(new SellerIntegration(sellerId, endpointUrl, current.secretVersion(), status, current.version(), current.createdAt(),
                current.updatedAt()));
    }

    @Override
    public SellerIntegration rotateSecret(UUID sellerId) {
        SellerIntegration current = existing(sellerId);
        return touch(new SellerIntegration(sellerId, current.endpointUrl(), current.secretVersion() + 1, current.status(), current.version(),
                current.createdAt(), current.updatedAt()));
    }

    /** Строка как в базе, без правил: для подготовки состояния теста. */
    public FakeIntegrations put(SellerIntegration integration) {
        rows.put(integration.sellerId(), integration);
        return this;
    }

    public SellerIntegration stored(UUID sellerId) {
        return rows.get(sellerId);
    }

    public int count() {
        return rows.size();
    }

    /** Сколько раз строки менялись: повтор без изменений не должен тревожить базу. */
    public int updates() {
        return updates;
    }

    private SellerIntegration existing(UUID sellerId) {
        requireLocked(sellerId);
        SellerIntegration current = rows.get(sellerId);
        if (current == null) {
            throw new IllegalStateException("Подключения продавца " + sellerId + " нет");
        }
        return current;
    }

    /** Триггер базы: версия записи растёт на единицу, время изменения ставит база. */
    private SellerIntegration touch(SellerIntegration changed) {
        updates++;
        SellerIntegration stored = new SellerIntegration(changed.sellerId(), changed.endpointUrl(), changed.secretVersion(), changed.status(),
                changed.version() + 1, changed.createdAt(), clock.instant());
        rows.put(stored.sellerId(), stored);
        return stored;
    }

    private void requireLocked(UUID sellerId) {
        if (!sellers.wasLockedById(sellerId)) {
            throw new IllegalStateException("Подключение продавца меняется без блокировки профиля: две гонки за подключение пройдут обе");
        }
    }

    private static void requireUrl(String url) {
        if (!url.matches("^https://\\S+$") || url.length() > 2048) {
            throw new IllegalStateException("ck_seller_integration_endpoint_url: адрес не https, с пробелом или длиннее 2048 знаков");
        }
    }
}
