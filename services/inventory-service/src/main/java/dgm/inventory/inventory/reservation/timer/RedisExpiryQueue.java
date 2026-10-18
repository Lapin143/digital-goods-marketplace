package dgm.inventory.inventory.reservation.timer;

import dgm.kit.redis.KitRedis;
import dgm.inventory.inventory.reservation.service.ExpiryQueue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Очередь сроков в отсортированном множестве Redis {@code reservation:expiry}: участник это идентификатор резерва, оценка это момент
 * срабатывания в миллисекундах от эпохи. Пользователь ACL {@code inventory} видит только ключи {@code reservation:*}.
 */
public final class RedisExpiryQueue implements ExpiryQueue {

    /** Ключ множества (ADR-012). */
    public static final String KEY = "reservation:expiry";

    private final KitRedis redis;

    public RedisExpiryQueue(KitRedis redis) {
        this.redis = redis;
    }

    @Override
    public void schedule(UUID reservationId, Instant expiresAt) {
        redis.zadd(KEY, expiresAt.toEpochMilli(), reservationId.toString());
    }

    @Override
    public List<UUID> due(Instant now, int limit) {
        List<UUID> due = new ArrayList<>();
        for (String member : redis.zrangeByScore(KEY, 0L, now.toEpochMilli(), limit)) {
            try {
                due.add(UUID.fromString(member));
            } catch (IllegalArgumentException e) {
                // Чужой участник в нашем ключе недопустим; убираем, чтобы он не занимал место в выборке
                redis.zrem(KEY, member);
            }
        }
        return due;
    }

    @Override
    public void remove(UUID reservationId) {
        redis.zrem(KEY, reservationId.toString());
    }
}
