package dgm.inventory.inventory.keys.domain;

import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.kit.secret.SecretValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Разбор загружаемого списка ключей (ADR-009, US-4.2, US-4.3): нормализация, проверка длины, дубли внутри загрузки. Дубли в пуле находит
 * база уникальным индексом {@code (product_id, hmac)}, а не этот класс.
 *
 * <p>Нормализация одна: пробелы и перевод строки по краям убираются, регистр значим. Пустое после этого значение это {@code empty},
 * длиннее {@value #MAX_KEY_LENGTH} знаков это {@code too_long}. Дубль внутри загрузки находится по HMAC (значение с идентификатором
 * товара), отклоняется повторное вхождение, первое остаётся.
 */
public final class KeyBatch {

    /** Сколько ключей в одной загрузке и строк в файле (ADR-009, уточнение 3). */
    public static final int MAX_KEYS = 10_000;
    /** Длина одного ключа в знаках. */
    public static final int MAX_KEY_LENGTH = 1024;
    /** Предел пула одного товара. */
    public static final int POOL_LIMIT = 100_000;

    private KeyBatch() {
    }

    /**
     * Принятый к вставке ключ.
     *
     * @param line  номер строки с единицы
     * @param value нормализованное значение
     * @param hmac  HMAC значения для товара
     */
    public record Candidate(int line, SecretValue value, byte[] hmac) {
    }

    /**
     * Итог разбора.
     *
     * @param candidates ключи к вставке в порядке строк
     * @param rejected   отклонённые строки в порядке номеров
     */
    public record Prepared(List<Candidate> candidates, List<RejectedLine> rejected) {
    }

    public static Prepared prepare(UUID productId, List<String> lines, KeyHasher hasher) {
        List<Candidate> candidates = new ArrayList<>();
        List<RejectedLine> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int number = 0;
        for (String raw : lines) {
            number++;
            String stripped = raw == null ? "" : raw.strip();
            if (stripped.isEmpty()) {
                rejected.add(new RejectedLine(number, RejectReason.EMPTY));
                continue;
            }
            if (stripped.length() > MAX_KEY_LENGTH) {
                rejected.add(new RejectedLine(number, RejectReason.TOO_LONG));
                continue;
            }
            SecretValue value = SecretValue.of(stripped);
            byte[] hmac = hasher.hmac(productId, value);
            if (!seen.add(KeyHasher.hex(hmac))) {
                value.destroy();
                rejected.add(new RejectedLine(number, RejectReason.DUPLICATE_IN_UPLOAD));
                continue;
            }
            candidates.add(new Candidate(number, value, hmac));
        }
        return new Prepared(List.copyOf(candidates), List.copyOf(rejected));
    }
}
