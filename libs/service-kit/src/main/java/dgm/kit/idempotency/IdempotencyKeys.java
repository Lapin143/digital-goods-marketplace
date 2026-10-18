package dgm.kit.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Ключи идемпотентности внутренних вызовов саги (Р-149). Фильтр идемпотентности принимает только UUID версии 4
 * ({@code conventions.md}, 3.5), а идентификаторы заказов это UUID версии 7. Ключ шага выводится из имени шага и идентификатора заказа:
 * повтор того же шага после сбоя или перезапуска даёт тот же ключ (сосед вернёт сохранённый ответ), а разные шаги одного заказа и
 * одинаковые шаги разных заказов не пересекаются. Случайный ключ здесь не годится: после перезапуска сервис не вспомнил бы его.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    /**
     * @param step    имя шага саги, например {@code reserve}, {@code confirm}, {@code open-session}; одно имя означает одну и ту же операцию
     * @param subject идентификатор, к которому относится шаг (заказ)
     * @return UUID версии 4 в каноническом виде, одинаковый для одинаковых аргументов
     */
    public static UUID derived(String step, UUID subject) {
        if (step == null || step.isBlank()) {
            throw new IllegalArgumentException("Имя шага не задано");
        }
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest((step + ":" + subject).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен в этой JVM", e);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        long high = 0;
        long low = 0;
        for (int i = 0; i < 8; i++) {
            high = (high << 8) | (hash[i] & 0xff);
            low = (low << 8) | (hash[8 + i] & 0xff);
        }
        return new UUID(high, low);
    }
}
