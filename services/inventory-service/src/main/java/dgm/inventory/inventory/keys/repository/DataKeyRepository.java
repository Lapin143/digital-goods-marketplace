package dgm.inventory.inventory.keys.repository;

import java.util.List;
import java.util.Optional;

/** Версии ключа данных (DEK) в {@code inventory.data_key}; в базе только DEK, зашифрованный мастер-ключом (ADR-009). */
public interface DataKeyRepository {

    /**
     * Версия ключа данных.
     *
     * @param id         номер версии
     * @param wrapped    DEK, зашифрованный KEK
     * @param kekVersion версия мастер-ключа, которым зашифрован DEK
     */
    record Stored(int id, byte[] wrapped, int kekVersion) {

        /** Запись под мастер-ключом первой версии (R1 без ротации). */
        public Stored(int id, byte[] wrapped) {
            this(id, wrapped, 1);
        }
    }

    Optional<Stored> active();

    Optional<Stored> byId(int id);

    /** Консультативная блокировка создания первого DEK до конца транзакции (Р-19): два экземпляра сервиса не создают его вдвоём. */
    void lockCreation();

    Stored insertActive(byte[] wrapped, int kekVersion);

    /** Все версии ключа данных, активные и выведенные: сервису при старте надо убедиться, что у него есть мастер-ключи для каждой. */
    List<Stored> all();

    /**
     * Консультативная блокировка ротации мастер-ключа до конца транзакции: два экземпляра сервиса не перешифровывают таблицу вдвоём.
     * Занятую блокировку не ждёт.
     *
     * @return {@code true}, если блокировка получена
     */
    boolean tryLockRotation();

    /** Версии ключа данных, зашифрованные мастер-ключом этой версии, с блокировкой строк до конца транзакции. */
    List<Stored> lockByKekVersion(int kekVersion);

    /** Заменяет обёртку DEK и версию мастер-ключа; сам DEK и его номер не меняются. */
    void rewrap(int id, byte[] wrapped, int kekVersion);
}
