package dgm.kit.idempotency;

import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище ключей идемпотентности (таблица {@code idempotency_key}). Операции {@link #claim}, {@link #find}, {@link #takeOver} и
 * {@link #release} выполняются каждая в своей короткой транзакции и видны другим запросам сразу. {@link #complete} вызывается
 * внутри транзакции действия: ответ фиксируется вместе с изменением данных (ADR-006, уровень 1).
 */
public interface IdempotencyStore {

    /** Запись о ключе. */
    record Entry(String endpoint, byte[] requestHash, boolean completed, StoredResponse response) {
    }

    /** Занять ключ: {@code true}, если записи не было и она создана в состоянии {@code in_progress}. */
    boolean claim(IdempotencyRequest request);

    Optional<Entry> find(String scope, UUID key);

    /**
     * Подхватить запрос, который выполняется дольше {@code abandonedAfterSeconds} (процесс упал, не освободив ключ).
     * Из нескольких одновременных повторов успех у одного.
     */
    boolean takeOver(String scope, UUID key, long abandonedAfterSeconds);

    /** Сохранить ответ и перевести ключ в {@code completed}. Вызывается в транзакции действия. */
    void complete(IdempotencyRequest request, StoredResponse response);

    /** Освободить занятый ключ после неуспеха, чтобы повтор выполнился заново. */
    void release(IdempotencyRequest request);

    /** Удалить записи старше срока хранения, вернуть число удалённых. */
    int purge(long olderThanSeconds);
}
