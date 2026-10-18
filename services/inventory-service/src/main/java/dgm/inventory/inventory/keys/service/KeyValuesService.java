package dgm.inventory.inventory.keys.service;

import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.crypto.OpenedKey;
import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.keys.repository.KeyRepository.StoredKey;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Значения ключей заказа для письма (компонент {@code key-pool}, {@code getKeyValues}, ADR-009, U17). Значения расшифровываются в
 * памяти и живут в {@link dgm.kit.secret.SecretValue} до отправки ответа; в журналы, события и метрики они не попадают, а исключения не содержат
 * значений. Вызывающего (только {@code delivery-service}) проверяет фильтр каркаса по сертификату клиента.
 */
@Component
public final class KeyValuesService {

    private static final String ISSUED = "issued";

    private final KeyRepository keys;
    private final DataKeys dataKeys;
    private final KeyCipher cipher;

    public KeyValuesService(KeyRepository keys, DataKeys dataKeys, KeyCipher cipher) {
        this.keys = keys;
        this.dataKeys = dataKeys;
        this.cipher = cipher;
    }

    /**
     * Значения всех ключей заказа в порядке выдачи.
     *
     * @throws ProblemException 404 у заказа нет ключей; 409 {@code state-conflict} ключи есть, но оплата ещё не подтверждена (не {@code issued})
     */
    public List<OpenedKey> valuesOf(UUID orderId) {
        List<StoredKey> stored = keys.byOrder(orderId);
        if (stored.isEmpty()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "У заказа нет выданных ключей.");
        }
        for (StoredKey key : stored) {
            if (!ISSUED.equals(key.status())) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Оплата заказа ещё не подтверждена, ключи не выданы.",
                        Map.of("currentStatus", key.status()), Map.of());
            }
        }
        List<OpenedKey> values = new ArrayList<>(stored.size());
        try {
            for (StoredKey key : stored) {
                values.add(new OpenedKey(key.id(), cipher.open(dataKeys.byId(key.dekId()), key.id(), key.productId(), key.nonce(), key.ciphertext())));
            }
        } catch (RuntimeException e) {
            destroy(values);
            throw e;
        }
        return values;
    }

    /** Стирает значения после ответа. */
    public static void destroy(List<OpenedKey> values) {
        for (OpenedKey value : values) {
            value.value().destroy();
        }
    }
}
