package dgm.inventory.inventory.crypto;

import dgm.kit.secret.SecretValue;
import java.util.UUID;

/**
 * Ключ заказа с расшифрованным значением. Значение живёт только в {@link SecretValue}; тот, кто получил запись, стирает его
 * ({@link SecretValue#destroy()}) после ответа.
 *
 * @param keyId идентификатор ключа
 * @param value значение ключа
 */
public record OpenedKey(UUID keyId, SecretValue value) {
}
