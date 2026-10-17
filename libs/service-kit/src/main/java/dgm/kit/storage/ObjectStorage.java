package dgm.kit.storage;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Объектное хранилище с интерфейсом S3 (ADR-024): сервис работает с ним через этот интерфейс и не знает, какой продукт за ним стоит.
 * Бакет закрытый, анонимного доступа нет; наружу файл отдаётся только по короткоживущей подписанной ссылке.
 *
 * <p>Недоступное хранилище или отказ в доступе это {@code dependency-unavailable} (503 с {@code Retry-After}): подробности попадают
 * в журнал, клиент их не видит.
 */
public interface ObjectStorage {

    /**
     * Ссылка на объект для скачивания.
     *
     * @param url       адрес с подписью в строке запроса; открывается без токена, пока не истёк срок
     * @param expiresAt момент, после которого хранилище отвергает ссылку
     */
    record PresignedLink(URI url, Instant expiresAt) {
    }

    /** Записывает объект целиком; объект с тем же ключом заменяется. */
    void put(String key, byte[] content, String contentType);

    /** Удаляет объект; отсутствующий объект не ошибка (удаление повторяемо). */
    void delete(String key);

    /**
     * Подписанная ссылка на скачивание. Подпись считается локально, хранилище не вызывается.
     *
     * @param ttl срок действия от текущего момента часов сервиса, от 1 секунды до 7 суток (предел SigV4)
     */
    PresignedLink presignGet(String key, Duration ttl);
}
