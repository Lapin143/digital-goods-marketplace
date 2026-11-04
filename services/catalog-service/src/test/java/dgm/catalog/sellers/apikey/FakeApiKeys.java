package dgm.catalog.sellers.apikey;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.catalog.sellers.apikey.domain.ApiKeyPage;
import dgm.catalog.sellers.apikey.domain.ApiKeyStatus;
import dgm.catalog.sellers.apikey.domain.KeyCheck;
import dgm.catalog.sellers.apikey.repository.ApiKeyRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий ключей в памяти для модульных тестов. Повторяет то, на что полагается сервис и что в базе держат ограничения: хеш ровно
 * 32 байта и без повторов ({@code ck_api_key_key_hash}, {@code uq_api_key_key_hash}), не больше пяти действующих ключей на продавца
 * (триггер {@code trg_api_key_limit}), отзыв только действующего ключа, порядок списка «новые выше» по паре (created_at, id).
 */
public final class FakeApiKeys implements ApiKeyRepository {

    private final FakeSellers sellers;
    private final Map<UUID, ApiKey> keys = new LinkedHashMap<>();
    private final Map<UUID, String> hashes = new LinkedHashMap<>();
    private int locks;

    public FakeApiKeys(FakeSellers sellers) {
        this.sellers = sellers;
    }

    @Override
    public int countActive(UUID sellerId) {
        // Счёт действующих ключей годится для решения «пятый или шестой» только под блокировкой строки профиля продавца
        if (!sellers.wasLockedById(sellerId)) {
            throw new IllegalStateException("Счёт действующих ключей без блокировки профиля продавца: два выпуска разом пройдут оба");
        }
        return (int) keys.values().stream().filter(k -> k.sellerId().equals(sellerId) && k.active()).count();
    }

    @Override
    public void insert(ApiKey key, byte[] keyHash) {
        if (keyHash.length != 32) {
            throw new IllegalStateException("ck_api_key_key_hash: хеш не 32 байта");
        }
        String hex = HexFormat.of().formatHex(keyHash);
        if (hashes.containsValue(hex) || keys.containsKey(key.id())) {
            throw new IllegalStateException("uq_api_key_key_hash: такой ключ уже есть");
        }
        if (!key.active()) {
            throw new IllegalStateException("trg_api_key_status_initial: ключ вставляется только действующим");
        }
        if (countActive(key.sellerId()) >= 5) {
            throw new IllegalStateException("ck_api_key_limit: у продавца уже пять активных API-ключей");
        }
        keys.put(key.id(), key);
        hashes.put(key.id(), hex);
    }

    @Override
    public Optional<ApiKey> find(UUID sellerId, UUID apiKeyId) {
        return Optional.ofNullable(keys.get(apiKeyId)).filter(k -> k.sellerId().equals(sellerId));
    }

    @Override
    public Optional<ApiKey> lock(UUID sellerId, UUID apiKeyId) {
        locks++;
        return find(sellerId, apiKeyId);
    }

    @Override
    public boolean markRevoked(ApiKey revoked) {
        ApiKey current = keys.get(revoked.id());
        if (current == null || !current.active() || revoked.active()) {
            return false;
        }
        keys.put(revoked.id(), revoked);
        return true;
    }

    @Override
    public List<ApiKey> list(UUID sellerId, Optional<ApiKeyStatus> status, Optional<ApiKeyPage.After> after, int limit) {
        Comparator<ApiKey> newestFirst = Comparator.comparing(ApiKey::createdAt).thenComparing(ApiKey::id).reversed();
        List<ApiKey> rows = new ArrayList<>(keys.values().stream().filter(k -> k.sellerId().equals(sellerId))
                .filter(k -> status.isEmpty() || k.status() == status.get()).sorted(newestFirst).toList());
        if (after.isPresent()) {
            ApiKey probe = new ApiKey(after.get().id(), sellerId, "p", "AAAAAAAA", ApiKeyStatus.ACTIVE, after.get().createdAt(), null);
            rows.removeIf(k -> newestFirst.compare(k, probe) <= 0);
        }
        return rows.size() > limit ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
    }

    @Override
    public Optional<KeyCheck> findByHash(byte[] keyHash) {
        String hex = HexFormat.of().formatHex(keyHash);
        return hashes.entrySet().stream().filter(e -> e.getValue().equals(hex)).findFirst().map(e -> keys.get(e.getKey()))
                .map(k -> new KeyCheck(k.id(), k.sellerId(), k.status(), sellers.findById(k.sellerId()).orElseThrow().status()));
    }

    /** Кладёт ключ с хешем как есть, минуя правила: для подготовки состояния теста. */
    public FakeApiKeys put(ApiKey key, byte[] keyHash) {
        keys.put(key.id(), key);
        hashes.put(key.id(), HexFormat.of().formatHex(keyHash));
        return this;
    }

    public ApiKey stored(UUID apiKeyId) {
        return keys.get(apiKeyId);
    }

    public int count() {
        return keys.size();
    }

    /** Всё, что «лежит в таблице»: строки ключей и их хеши в шестнадцатеричном виде, для проверки, что значения среди них нет. */
    public String dump() {
        return keys.values() + " " + hashes.values();
    }

    public int lockCalls() {
        return locks;
    }
}
