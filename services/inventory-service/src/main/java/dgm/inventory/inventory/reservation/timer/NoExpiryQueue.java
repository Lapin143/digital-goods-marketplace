package dgm.inventory.inventory.reservation.timer;

import dgm.inventory.inventory.reservation.service.ExpiryQueue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Очередь без Redis (клиент Redis не настроен): сроки ведёт только сверка по базе. */
public final class NoExpiryQueue implements ExpiryQueue {

    @Override
    public void schedule(UUID reservationId, Instant expiresAt) {
        // Нечего запоминать: резерв найдёт сверка
    }

    @Override
    public List<UUID> due(Instant now, int limit) {
        return List.of();
    }

    @Override
    public void remove(UUID reservationId) {
        // Нечего удалять
    }
}
