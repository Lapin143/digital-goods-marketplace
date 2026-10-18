package dgm.inventory.inventory.reservation.service;

import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.registry.ProductCopyRepository;
import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.domain.ReservationSettings;
import dgm.inventory.inventory.reservation.repository.ReservationRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import dgm.inventory.inventory.service.StockPublisher;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Резерв ключей за заказом (компонент {@code reservation-service}, ADR-007, ADR-012, SM-08, SM-03). Каждое действие это одна
 * транзакция модуля: блокировка строки товара, затем строки резерва и ключей, изменение, версия остатка, события в Outbox. Строка
 * товара блокируется первой во всех путях (резерв, истечение, снятие), поэтому взаимных блокировок нет, а версии остатка идут в
 * порядке фиксации.
 *
 * <ul>
 *   <li>{@link #reserve}: свободных ключей достаточно, иначе 409 {@code insufficient-stock} и ничего не создаётся (INV-05); у заказа
 *       не больше одного активного резерва (INV-06); срок от часов сервиса и параметра {@code reservation.ttl-seconds};
 *   <li>{@link #expire}: резерв активен и срок наступил, иначе ничего не меняется: условный {@code UPDATE} заменяет распределённую
 *       блокировку, из двух исполнителей (таймер и сверка, два экземпляра) резерв снимает один;
 *   <li>{@link #release}: снятие по отмене заказа или отказу в оплате, уже снятый и использованный резерв не трогается, поэтому
 *       повтор события и порядок событий безопасны;
 *   <li>{@link #confirm}: подтверждение после оплаты (ADR-013). Активный резерв становится использованным, ключи выданными; если
 *       резерв уже снят (поздняя оплата), в той же транзакции берутся свободные ключи и создаётся новый резерв сразу использованным;
 *       ключей не хватило: 409 {@code insufficient-stock}, ничего не создаётся и не меняется.
 * </ul>
 *
 * <p>Таймер в Redis ставится после фиксации и убирается после фиксации; ошибка Redis бизнес-действие не ломает (источник истины база),
 * её считает метрика {@code reservation_timer_errors_total}.
 */
@Component
public final class ReservationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationService.class);

    /** Тема, агрегат и версия схемы событий резерва (контракт событий). */
    static final String TOPIC = "inventory.events";
    static final String AGGREGATE = "Reservation";
    static final String RESERVATION_EXPIRED = "reservation.expired";
    static final String RESERVATION_RELEASED = "reservation.released";
    static final int SCHEMA_VERSION = 1;

    /** Итог попытки снять резерв по сроку. */
    public enum Expiry {
        /** Резерв был активным, срок наступил: снят, ключи свободны, события записаны. */
        EXPIRED,
        /** Резерв ещё активен, но срок не наступил (запись в очереди чуть опередила базу): повторить позже. */
        NOT_DUE,
        /** Резерва нет или он уже не активный: его снял другой исполнитель, использовала оплата или отмена. */
        GONE
    }

    /** Что обнаружило истечение срока (поле {@code expiredBy} события). */
    public enum Detected {
        TIMER("timer"),
        RECONCILIATION("reconciliation");

        private final String code;

        Detected(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Итог подтверждения.
     *
     * @param reservation использованный резерв заказа
     * @param reReserved  {@code true}, если резерв был снят и при поздней оплате создан заново (SEQ-02)
     */
    public record Confirmation(Reservation reservation, boolean reReserved) {
    }

    private final ProductCopyRepository products;
    private final ReservationRepository reservations;
    private final KeyRepository keys;
    private final StockPublisher stockEvents;
    private final InventoryTransaction transaction;
    private final ExpiryQueue queue;
    private final ReservationSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public ReservationService(ProductCopyRepository products, ReservationRepository reservations, KeyRepository keys, StockPublisher stockEvents,
                              InventoryTransaction transaction, ExpiryQueue queue, ReservationSettings settings, Clock clock, MeterRegistry meters) {
        this.products = products;
        this.reservations = reservations;
        this.keys = keys;
        this.stockEvents = stockEvents;
        this.transaction = transaction;
        this.queue = queue;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Резервирует {@code quantity} ключей товара за заказом (SM-08/T1, SM-03/T2).
     *
     * @throws ProblemException 404 нет такого товара; 409 {@code product-unavailable} товар не опубликован, {@code state-conflict} у заказа
     *                          уже есть активный или использованный резерв или товар не из пула, {@code insufficient-stock} свободных
     *                          ключей меньше заказанного (с полем {@code available})
     */
    public Reservation reserve(UUID orderId, UUID productId, int quantity) {
        Reservation created = transaction.execute(() -> {
            ProductCopy product = products.lock(productId).orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Товар не найден."));
            if (!product.keyPool()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Выдача этого товара не из пула ключей, резерв не создаётся.");
            }
            if (!product.published()) {
                throw new ProblemException(ProblemType.PRODUCT_UNAVAILABLE, "Товар сейчас недоступен для покупки.",
                        Map.of("currentStatus", product.status()), Map.of());
            }
            Optional<Reservation> latest = reservations.latestByOrder(orderId);
            if (latest.isPresent() && !latest.get().isReleased()) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "У заказа уже есть резерв ключей.",
                        Map.of("currentStatus", latest.get().status()), Map.of());
            }
            int available = freeKeys(productId);
            if (available < quantity) {
                throw new ProblemException(ProblemType.INSUFFICIENT_STOCK, "Свободных ключей меньше заказанного количества.",
                        Map.of("available", available), Map.of());
            }
            Instant now = clock.instant();
            Reservation reservation = new Reservation(Uuids.v7(clock), orderId, productId, quantity, Reservation.ACTIVE, now.plus(settings.ttl()), null);
            reservations.insert(reservation, now);
            List<UUID> picked = keys.reserveFree(productId, quantity, orderId, reservation.id());
            if (picked.size() != quantity) {
                // Под блокировкой строки товара такого не бывает; откат важнее частичного резерва (INV-05)
                throw new IllegalStateException("Закреплено ключей " + picked.size() + " вместо " + quantity + ", резерв откатывается");
            }
            stockEvents.publish(productId, available - quantity);
            transaction.afterCommit(() -> schedule(reservation));
            return reservation;
        });
        meters.counter("reservations_created_total").increment();
        return created;
    }

    /**
     * Снимает резерв по сроку (SM-08/T3, SM-03/T3), если он активен и срок наступил. Событие {@code reservation.expired} читает
     * оркестратор заказа, {@code stock.changed} витрина. Вызывается таймером и сверкой; безопасен при двух экземплярах сервиса.
     */
    public Expiry expire(UUID reservationId, Detected by) {
        Expiry result = transaction.execute(() -> {
            Optional<Reservation> current = reservations.find(reservationId);
            if (current.isEmpty()) {
                transaction.afterCommit(() -> unschedule(reservationId));
                return Expiry.GONE;
            }
            products.lock(current.get().productId());
            Optional<Reservation> released = reservations.releaseIfDue(reservationId, clock.instant());
            if (released.isEmpty()) {
                // Пока ждали блокировку товара, резерв могли снять или использовать
                boolean stillActive = reservations.find(reservationId).filter(Reservation::isActive).isPresent();
                if (!stillActive) {
                    transaction.afterCommit(() -> unschedule(reservationId));
                }
                return stillActive ? Expiry.NOT_DUE : Expiry.GONE;
            }
            Reservation reservation = released.get();
            keys.releaseByReservation(reservation.id());
            stockEvents.publish(reservation.productId(), freeKeys(reservation.productId()));
            Map<String, Object> data = eventData(reservation);
            data.put("expiredBy", by.code());
            transaction.publish(TOPIC, AGGREGATE, reservation.orderId().toString(), RESERVATION_EXPIRED, SCHEMA_VERSION, data);
            transaction.afterCommit(() -> unschedule(reservationId));
            return Expiry.EXPIRED;
        });
        if (result == Expiry.EXPIRED) {
            meters.counter("reservations_expired_total", "by", by.code()).increment();
        }
        return result;
    }

    /**
     * Снимает активный резерв заказа по причине {@code payment_declined} или {@code order_cancelled} (SM-08/T4, T6; SM-03/T3).
     * Резерв заказа уже снят (срок истёк) или использован: ничего не меняется.
     *
     * @return снятый резерв; пусто, если снимать было нечего
     */
    public Optional<Reservation> release(UUID orderId, String reason) {
        Optional<Reservation> result = transaction.<Optional<Reservation>>execute(() -> {
            Optional<Reservation> active = reservations.activeByOrder(orderId);
            if (active.isEmpty()) {
                return Optional.empty();
            }
            products.lock(active.get().productId());
            Optional<Reservation> released = reservations.release(active.get().id(), reason, clock.instant());
            if (released.isEmpty()) {
                return Optional.empty();
            }
            Reservation reservation = released.get();
            keys.releaseByReservation(reservation.id());
            stockEvents.publish(reservation.productId(), freeKeys(reservation.productId()));
            Map<String, Object> data = eventData(reservation);
            data.put("reason", reason);
            transaction.publish(TOPIC, AGGREGATE, reservation.orderId().toString(), RESERVATION_RELEASED, SCHEMA_VERSION, data);
            transaction.afterCommit(() -> unschedule(reservation.id()));
            return Optional.of(reservation);
        });
        result.ifPresent(r -> meters.counter("reservations_released_total", "reason", reason).increment());
        return result;
    }

    /**
     * Подтверждает резерв заказа после оплаты (SM-08/T2, SM-03/T4, ADR-013): одна атомарная операция. Строка товара блокируется
     * первой, как и в других путях, поэтому подтверждение и истечение одного резерва выстраиваются в очередь и дают ровно один исход:
     * подтверждение успело (резерв использован, истечение ничего не меняет) или истечение успело (подтверждение берёт свободные ключи и
     * создаёт новый резерв). Повтор после успеха возвращает тот же резерв без изменений.
     *
     * @throws ProblemException 404 нет товара или у заказа никогда не было резерва; 409 {@code state-conflict} товар или количество не
     *                          совпадают с резервом заказа, {@code insufficient-stock} при поздней оплате свободных ключей меньше
     *                          заказанного (с полем {@code available})
     */
    public Confirmation confirm(UUID orderId, UUID productId, int quantity) {
        return transaction.execute(() -> {
            products.lock(productId).orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Товар не найден."));
            Reservation latest = reservations.latestByOrder(orderId)
                    .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Резерв этого заказа не найден."));
            if (!latest.productId().equals(productId) || latest.quantity() != quantity) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Товар и количество не совпадают с резервом заказа.");
            }
            if (latest.isUsed()) {
                return new Confirmation(latest, false);
            }
            Instant now = clock.instant();
            if (latest.isActive()) {
                Reservation used = reservations.markUsed(latest.id(), now)
                        .orElseThrow(() -> new IllegalStateException("Активный резерв " + latest.id() + " не удалось использовать под блокировкой товара"));
                issue(used, now);
                stockEvents.publish(productId, freeKeys(productId));
                transaction.afterCommit(() -> {
                    unschedule(used.id());
                    meters.counter("reservations_confirmed_total", "reReserved", "false").increment();
                });
                return new Confirmation(used, false);
            }
            // Резерв снят (срок истёк или заказ отменён), а оплата пришла: оплаченный заказ получает ключи, если они ещё свободны
            int available = freeKeys(productId);
            if (available < quantity) {
                throw new ProblemException(ProblemType.INSUFFICIENT_STOCK, "Свободных ключей меньше заказанного количества.",
                        Map.of("available", available), Map.of());
            }
            Reservation fresh = new Reservation(Uuids.v7(clock), orderId, productId, quantity, Reservation.ACTIVE, now.plus(settings.ttl()), null);
            reservations.insert(fresh, now);
            List<UUID> picked = keys.reserveFree(productId, quantity, orderId, fresh.id());
            if (picked.size() != quantity) {
                throw new IllegalStateException("Закреплено ключей " + picked.size() + " вместо " + quantity + ", поздняя оплата откатывается");
            }
            Reservation used = reservations.markUsed(fresh.id(), now)
                    .orElseThrow(() -> new IllegalStateException("Новый резерв " + fresh.id() + " не удалось использовать"));
            issue(used, now);
            stockEvents.publish(productId, available - quantity);
            transaction.afterCommit(() -> meters.counter("reservations_confirmed_total", "reReserved", "true").increment());
            return new Confirmation(used, true);
        });
    }

    /** Ключи резерва становятся выданными; их ровно столько, сколько в резерве, иначе транзакция откатывается (INV-05). */
    private void issue(Reservation used, Instant at) {
        int issued = keys.issueByReservation(used.id(), at);
        if (issued != used.quantity()) {
            throw new IllegalStateException("Выдано ключей " + issued + " вместо " + used.quantity() + ", подтверждение откатывается");
        }
    }

    private int freeKeys(UUID productId) {
        return keys.countByStatus(productId).getOrDefault("free", 0);
    }

    private static Map<String, Object> eventData(Reservation reservation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", reservation.id().toString());
        data.put("orderId", reservation.orderId().toString());
        data.put("productId", reservation.productId().toString());
        data.put("quantity", reservation.quantity());
        return data;
    }

    private void schedule(Reservation reservation) {
        try {
            queue.schedule(reservation.id(), reservation.expiresAt());
        } catch (RuntimeException e) {
            meters.counter("reservation_timer_errors_total", "op", "schedule").increment();
            LOG.warn("Таймер резерва {} не поставлен, срок подхватит сверка: {}", reservation.id(), e.getClass().getSimpleName());
        }
    }

    private void unschedule(UUID reservationId) {
        try {
            queue.remove(reservationId);
        } catch (RuntimeException e) {
            meters.counter("reservation_timer_errors_total", "op", "remove").increment();
            LOG.warn("Таймер резерва {} не убран, лишняя запись очереди безвредна: {}", reservationId, e.getClass().getSimpleName());
        }
    }
}
