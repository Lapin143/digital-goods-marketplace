package dgm.inventory.inventory.keys.repository;

import java.util.Optional;

/** Версии ключа данных (DEK) в {@code inventory.data_key}; в базе только DEK, зашифрованный мастер-ключом (ADR-009). */
public interface DataKeyRepository {

    /**
     * Версия ключа данных.
     *
     * @param id      номер версии
     * @param wrapped DEK, зашифрованный KEK
     */
    record Stored(int id, byte[] wrapped) {
    }

    Optional<Stored> active();

    Optional<Stored> byId(int id);

    /** Консультативная блокировка создания первого DEK до конца транзакции (Р-19): два экземпляра сервиса не создают его вдвоём. */
    void lockCreation();

    Stored insertActive(byte[] wrapped, int kekVersion);
}
