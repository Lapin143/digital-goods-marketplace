package dgm.platform.support.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Состояние очереди одним запросом: сколько обращений открыто и с какого времени ждёт самое старое необработанное (метрики очереди,
 * Р-163, NFT-6.1).
 *
 * @param created       обращений в статусе {@code created}
 * @param inProgress    обращений в статусе {@code in_progress}
 * @param oldestCreated время создания самого старого обращения в статусе {@code created}; пусто, если очередь пуста
 */
public record QueueDepth(long created, long inProgress, Optional<Instant> oldestCreated) {
}
