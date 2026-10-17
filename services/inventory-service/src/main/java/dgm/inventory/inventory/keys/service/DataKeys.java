package dgm.inventory.inventory.keys.service;

import dgm.inventory.inventory.crypto.CryptoException;
import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.keys.repository.DataKeyRepository;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Ключи данных (DEK) для шифрования значений (ADR-009). Активный DEK один; если его ещё нет, он создаётся под консультативной
 * блокировкой базы (Р-19): два экземпляра сервиса не создадут по ключу, а уникальный индекс {@code uq_data_key_active} остаётся второй
 * защитой. Расшифрованные DEK держатся в памяти процесса, как и мастер-ключ; в журналы и события они не попадают.
 *
 * <p>Методы работают внутри транзакции вызывающего: создание первого DEK фиксируется вместе с первой загрузкой ключей.
 */
@Component
public final class DataKeys {

    /** Версия мастер-ключа в R1 одна; смена KEK и перешифровка DEK войдут в срез 3. */
    static final int KEK_VERSION = 1;

    private final DataKeyRepository repository;
    private final KeyCipher cipher;
    private final Map<Integer, byte[]> opened = new ConcurrentHashMap<>();

    public DataKeys(DataKeyRepository repository, KeyCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /** Активный DEK в открытом виде вместе с номером версии; при первом обращении создаёт его. */
    public Active active() {
        DataKeyRepository.Stored stored = repository.active().orElse(null);
        if (stored == null) {
            repository.lockCreation();
            // Пока ждали блокировку, другой экземпляр мог создать ключ
            stored = repository.active().orElseGet(() -> repository.insertActive(cipher.wrap(cipher.newDek()), KEK_VERSION));
        }
        return new Active(stored.id(), open(stored));
    }

    /** DEK по номеру версии (для чтения старых значений). */
    public byte[] byId(int id) {
        byte[] cached = opened.get(id);
        if (cached != null) {
            return cached;
        }
        return open(repository.byId(id).orElseThrow(() -> new CryptoException("Ключ данных не найден")));
    }

    private byte[] open(DataKeyRepository.Stored stored) {
        return opened.computeIfAbsent(stored.id(), id -> cipher.unwrap(stored.wrapped()));
    }

    /**
     * Активный ключ данных.
     *
     * @param id  номер версии, записывается в {@code key.dek_id}
     * @param dek ключ в открытом виде; массив принадлежит кэшу, вызывающий его не меняет
     */
    public record Active(int id, byte[] dek) {
    }
}
