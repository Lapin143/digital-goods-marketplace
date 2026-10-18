package dgm.inventory.inventory.reservation.repository;

import dgm.inventory.inventory.reservation.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Резервы в {@code inventory.reservation}. Переходы статуса это условные обновления ({@code … WHERE status = 'active'}): они заменяют
 * распределённую блокировку, из двух одновременных исполнителей изменение применяется ровно у одного. Вызывать их надо в транзакции,
 * где строка товара уже заблокирована ({@code ProductCopyRepository.lock}): так порядок блокировок один и тот же во всех путях.
 */
public interface ReservationRepository {

    Optional<Reservation> find(UUID id);

    /** Последний по времени создания резерв заказа в любом статусе. */
    Optional<Reservation> latestByOrder(UUID orderId);

    /** Активный резерв заказа (INV-06: не больше одного). */
    Optional<Reservation> activeByOrder(UUID orderId);

    /** Создаёт активный резерв; {@code createdAt} и {@code expiresAt} задают часы сервиса. */
    void insert(Reservation reservation, Instant createdAt);

    /**
     * Снимает активный резерв, срок которого наступил: {@code UPDATE … SET status = 'released', release_reason = 'expired' WHERE id = ?
     * AND status = 'active' AND expires_at <= :now RETURNING …}.
     *
     * @return снятый резерв; пусто, если он уже не активный или срок ещё не наступил
     */
    Optional<Reservation> releaseIfDue(UUID id, Instant now);

    /**
     * Снимает активный резерв по причине {@code payment_declined} или {@code order_cancelled}.
     *
     * @return снятый резерв; пусто, если он уже не активный
     */
    Optional<Reservation> release(UUID id, String reason, Instant at);

    /** Идентификаторы активных резервов, срок которых вышел не позже {@code dueBefore}, по возрастанию срока (сверка). */
    List<UUID> overdue(Instant dueBefore, int limit);

    /** Активные резервы с идентификатором больше {@code after}, по возрастанию идентификатора: перезагрузка таймеров при старте. */
    List<Reservation> activeAfter(UUID after, int limit);
}
