package dgm.inventory.inventory.keys;

import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.keys.repository.DataKeyRepository;
import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.registry.ProductCopyRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Память вместо базы и Outbox для проверок сервиса пула: те же правила уникальности, что у {@code (product_id, hmac)}. */
public final class FakePool {

    final Products products = new Products();
    final Keys keys = new Keys();
    final DataKeys dataKeys = new DataKeys();
    final Transaction transaction = new Transaction();

    static final class Products implements ProductCopyRepository {
        final Map<UUID, ProductCopy> rows = new HashMap<>();
        final List<UUID> locked = new ArrayList<>();

        @Override
        public Optional<ProductCopy> find(UUID productId) {
            return Optional.ofNullable(rows.get(productId));
        }

        @Override
        public Optional<ProductCopy> lock(UUID productId) {
            locked.add(productId);
            return find(productId);
        }

        @Override
        public boolean apply(ProductCopy copy, Instant at) {
            ProductCopy old = rows.get(copy.productId());
            if (old != null && old.sourceVersion() >= copy.sourceVersion()) {
                return false;
            }
            rows.put(copy.productId(), copy);
            return true;
        }

        @Override
        public boolean setStatus(UUID productId, String status, Instant at) {
            ProductCopy old = rows.get(productId);
            if (old == null) {
                return false;
            }
            rows.put(productId, new ProductCopy(productId, old.sellerId(), old.issuanceMethod(), status, old.sourceVersion()));
            return true;
        }
    }

    static final class Keys implements KeyRepository {
        final Map<UUID, Map<String, NewKey>> byProduct = new HashMap<>();
        /** Статусы, которых загрузка не создаёт (зарезервированные и выданные подставляются тестом). */
        final Map<UUID, Map<String, Integer>> extra = new HashMap<>();

        @Override
        public Map<String, Integer> countByStatus(UUID productId) {
            Map<String, Integer> counts = new HashMap<>(extra.getOrDefault(productId, Map.of()));
            int free = byProduct.getOrDefault(productId, Map.of()).size();
            if (free > 0) {
                counts.merge("free", free, Integer::sum);
            }
            return counts;
        }

        @Override
        public Set<String> insertNew(UUID productId, List<NewKey> rows) {
            Map<String, NewKey> pool = byProduct.computeIfAbsent(productId, id -> new LinkedHashMap<>());
            Set<String> inserted = new HashSet<>();
            for (NewKey row : rows) {
                String hmac = KeyHasher.hex(row.hmac());
                if (!pool.containsKey(hmac)) {
                    pool.put(hmac, row);
                    inserted.add(hmac);
                }
            }
            return inserted;
        }

        int stored(UUID productId) {
            return byProduct.getOrDefault(productId, Map.of()).size();
        }
    }

    static final class DataKeys implements DataKeyRepository {
        final List<Stored> rows = new ArrayList<>();
        int locks;

        @Override
        public Optional<Stored> active() {
            return rows.stream().findFirst();
        }

        @Override
        public Optional<Stored> byId(int id) {
            return rows.stream().filter(s -> s.id() == id).findFirst();
        }

        @Override
        public void lockCreation() {
            locks++;
        }

        @Override
        public Stored insertActive(byte[] wrapped, int kekVersion) {
            Stored stored = new Stored(rows.size() + 1, wrapped);
            rows.add(stored);
            return stored;
        }
    }

    static final class Transaction implements InventoryTransaction {
        record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
        }

        final List<Event> events = new ArrayList<>();
        boolean inside;

        @Override
        public <T> T execute(Supplier<T> action) {
            inside = true;
            try {
                return action.get();
            } finally {
                inside = false;
            }
        }

        @Override
        public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
            if (!inside) {
                throw new IllegalStateException("вне транзакции");
            }
            events.add(new Event(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
        }
    }
}
