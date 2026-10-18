package dgm.inventory.inventory.crypto;

import dgm.kit.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Тело ответа {@code getKeyValues} (схема {@code KeyValues}). Единственное место сервиса, где открытое значение ключа читается из
 * {@link dgm.kit.secret.SecretValue} для ответа: правило архитектуры разрешает чтение секрета только в модуле шифрования, поэтому
 * сборка тела лежит здесь, а не в контроллере.
 */
public final class KeyValuesDocument {

    private KeyValuesDocument() {
    }

    /** JSON тела: идентификатор заказа и ключи в переданном порядке. Значения после сборки остаются у вызывающего, он их стирает. */
    public static String json(UUID orderId, List<OpenedKey> keys) {
        List<Map<String, Object>> items = new ArrayList<>(keys.size());
        for (OpenedKey key : keys) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyId", key.keyId().toString());
            item.put("value", key.value().reveal());
            items.add(item);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("orderId", orderId.toString());
        document.put("keys", items);
        return Json.write(document);
    }
}
