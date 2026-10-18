package dgm.inventory.inventory.reservation;

import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.registry.ProductCopyRepository;
import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.domain.ReservationSettings;
import dgm.inventory.inventory.reservation.repository.ReservationRepository;
import dgm.inventory.inventory.reservation.service.ExpiryQueue;
import dgm.inventory.inventory.reservation.service.ReservationService;
import dgm.inventory.inventory.service.InventoryTransaction;
import dgm.inventory.inventory.service.StockPublisher;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Память вместо базы, Outbox и Redis для проверок сервиса резерва. Правила те же, что у SQL: условные переходы статуса, ключи
 * {@code free → reserved → free}, версия остатка растёт на единицу при каждом изменении пула, события и отложенные действия
 * появляются только после успешного завершения транзакции (при исключении всё отбрасывается, как откат).
 */
final class FakeStock {

    static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    static final Instant START = Instant.parse("2026-10-17T12:00:00Z");

    final ManualClock clock = new ManualClock(START);
    final Products products = new Products();
    final Reservations reservations = new Reservations();
    final Keys keys = new Keys();
    final Queue queue = new Queue();
    final Tx transaction = new Tx();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ReservationService service;

    FakeStock(ReservationSettings settings) {
        products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", "published", 1));
        service = new ReservationService(products, reservations, keys, new StockPublisher(products, transaction, clock), transaction, queue,
                settings, clock, meters);
    }

    /** Версия остатка товара: сколько раз менялся пул. */
    long version() {
        return products.versions.getOrDefault(PRODUCT, 0L);
    }

    List<Tx.Event> events(String type) {
        return transaction.events.stream().filter(e -> e.type().equals(type)).toList();
    }

    static final class Products implements ProductCopyRepository {
        final Map<UUID, ProductCopy> rows = new HashMap<>();
        final Map<UUID, Long> versions = new HashMap<>();
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
            rows.put(copy.productId(), copy);
            return true;
        }

        @Override
        public long bumpStockVersion(UUID productId, Instant at) {
            return versions.merge(productId, 1L, Long::sum);
        }

        @Override
        public boolean setStatus(UUID productId, String status, Instant at) {
            ProductCopy old = rows.get(productId);
            rows.put(productId, new ProductCopy(productId, old.sellerId(), old.issuanceMethod(), status, old.sourceVersion()));
            return true;
        }
    }

    static final class Reservations implements ReservationRepository {
        final Map<UUID, Reservation> rows = new LinkedHashMap<>();

        @Override
        public Optional<Reservation> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Reservation> latestByOrder(UUID orderId) {
            Reservation latest = null;
            for (Reservation r : rows.values()) {
                if (r.orderId().equals(orderId)) {
                    latest = r;
                }
            }
            return Optional.ofNullable(latest);
        }

        @Override
        public Optional<Reservation> activeByOrder(UUID orderId) {
            return rows.values().stream().filter(r -> r.orderId().equals(orderId) && r.isActive()).findFirst();
        }

        @Override
        public void insert(Reservation reservation, Instant createdAt) {
            rows.put(reservation.id(), reservation);
        }

        @Override
        public Optional<Reservation> releaseIfDue(UUID id, Instant now) {
            Reservation r = rows.get(id);
            if (r == null || !r.isActive() || r.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            return Optional.of(replace(r, Reservation.RELEASED, Reservation.EXPIRED));
        }

        @Override
        public Optional<Reservation> release(UUID id, String reason, Instant at) {
            Reservation r = rows.get(id);
            if (r == null || !r.isActive()) {
                return Optional.empty();
            }
            return Optional.of(replace(r, Reservation.RELEASED, reason));
        }

        @Override
        public List<UUID> overdue(Instant dueBefore, int limit) {
            return rows.values().stream().filter(r -> r.isActive() && !r.expiresAt().isAfter(dueBefore)).map(Reservation::id).limit(limit).toList();
        }

        @Override
        public List<Reservation> activeAfter(UUID after, int limit) {
            return rows.values().stream().filter(r -> r.isActive() && r.id().compareTo(after) > 0).sorted((a, b) -> a.id().compareTo(b.id()))
                    .limit(limit).toList();
        }

        /** Использованный резерв (оплата подтверждена): для проверки, что снятие его не трогает. */
        void markUsed(UUID id) {
            Reservation r = rows.get(id);
            rows.put(id, new Reservation(r.id(), r.orderId(), r.productId(), r.quantity(), Reservation.USED, r.expiresAt(), null));
        }

        private Reservation replace(Reservation r, String status, String reason) {
            Reservation changed = new Reservation(r.id(), r.orderId(), r.productId(), r.quantity(), status, r.expiresAt(), reason);
            rows.put(r.id(), changed);
            return changed;
        }
    }

    static final class Keys implements KeyRepository {
        private final Map<UUID, List<UUID>> free = new HashMap<>();
        private final Map<UUID, List<UUID>> heldByReservation = new HashMap<>();
        private final Map<UUID, UUID> productOfReservation = new HashMap<>();

        /** Добавляет в пул товара {@code count} свободных ключей. */
        void load(UUID productId, int count) {
            List<UUID> pool = free.computeIfAbsent(productId, id -> new ArrayList<>());
            for (int i = 0; i < count; i++) {
                pool.add(UUID.randomUUID());
            }
        }

        int free(UUID productId) {
            return free.getOrDefault(productId, List.of()).size();
        }

        int held(UUID reservationId) {
            return heldByReservation.getOrDefault(reservationId, List.of()).size();
        }

        @Override
        public Map<String, Integer> countByStatus(UUID productId) {
            Map<String, Integer> counts = new HashMap<>();
            int freeCount = free(productId);
            if (freeCount > 0) {
                counts.put("free", freeCount);
            }
            int reserved = 0;
            for (Map.Entry<UUID, List<UUID>> held : heldByReservation.entrySet()) {
                if (productOfReservation.get(held.getKey()).equals(productId)) {
                    reserved += held.getValue().size();
                }
            }
            if (reserved > 0) {
                counts.put("reserved", reserved);
            }
            return counts;
        }

        @Override
        public List<UUID> reserveFree(UUID productId, int quantity, UUID orderId, UUID reservationId) {
            List<UUID> pool = free.computeIfAbsent(productId, id -> new ArrayList<>());
            List<UUID> picked = new ArrayList<>(pool.subList(0, Math.min(quantity, pool.size())));
            pool.removeAll(picked);
            heldByReservation.put(reservationId, picked);
            productOfReservation.put(reservationId, productId);
            return picked;
        }

        @Override
        public int releaseByReservation(UUID reservationId) {
            List<UUID> held = heldByReservation.remove(reservationId);
            if (held == null) {
                return 0;
            }
            free.computeIfAbsent(productOfReservation.get(reservationId), id -> new ArrayList<>()).addAll(held);
            return held.size();
        }

        @Override
        public Set<String> insertNew(UUID productId, List<NewKey> keys) {
            throw new UnsupportedOperationException("загрузка пула проверяется в KeyPoolServiceTest");
        }
    }

    /** Очередь сроков в памяти; {@code failing = true} имитирует недоступный Redis. */
    static final class Queue implements ExpiryQueue {
        final Map<UUID, Instant> scheduled = new LinkedHashMap<>();
        boolean failing;

        @Override
        public void schedule(UUID reservationId, Instant expiresAt) {
            check();
            scheduled.put(reservationId, expiresAt);
        }

        @Override
        public List<UUID> due(Instant now, int limit) {
            check();
            return scheduled.entrySet().stream().filter(e -> !e.getValue().isAfter(now)).map(Map.Entry::getKey).limit(limit).toList();
        }

        @Override
        public void remove(UUID reservationId) {
            check();
            scheduled.remove(reservationId);
        }

        private void check() {
            if (failing) {
                throw new IllegalStateException("Redis недоступен");
            }
        }
    }

    static final class Tx implements InventoryTransaction {
        record Event(String topic, String aggregateType, String aggregateId, String type, int schemaVersion, Map<String, Object> data) {
        }

        final List<Event> events = new ArrayList<>();
        private List<Event> pending;
        private List<Runnable> after;

        @Override
        public <T> T execute(Supplier<T> action) {
            if (pending != null) {
                return action.get();
            }
            pending = new ArrayList<>();
            after = new ArrayList<>();
            T result;
            try {
                result = action.get();
            } catch (RuntimeException e) {
                pending = null;
                after = null;
                throw e;
            }
            events.addAll(pending);
            List<Runnable> run = after;
            pending = null;
            after = null;
            run.forEach(Runnable::run);
            return result;
        }

        @Override
        public void afterCommit(Runnable action) {
            if (after == null) {
                action.run();
            } else {
                after.add(action);
            }
        }

        @Override
        public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
            if (pending == null) {
                throw new IllegalStateException("вне транзакции");
            }
            pending.add(new Event(topic, aggregateType, aggregateId, eventType, schemaVersion, data));
        }
    }
}
