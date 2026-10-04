package dgm.kit.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Значение, которое нельзя напечатать и нельзя сериализовать: ключ, пароль, секрет подписи.
 *
 * <p>{@link #toString()} всегда возвращает маску, класс не реализует {@code Serializable} и не имеет свойств для JSON, а сравнение идёт
 * за постоянное время. Значение читается явным вызовом {@link #reveal()}: ArchUnit-правило {@code ServiceArchRules} разрешает его
 * только в классах, перечисленных сервисом (модуль шифрования, TLS), поэтому попасть в журнал оно может только через код, который
 * ревьюер видит сразу (c4-components.md, раздел 3, правило 8).
 */
public final class SecretValue {

    private static final String MASK = "SecretValue[****]";

    private final byte[] bytes;

    private SecretValue(byte[] bytes) {
        this.bytes = bytes;
    }

    public static SecretValue of(String value) {
        Objects.requireNonNull(value, "value");
        return new SecretValue(value.getBytes(StandardCharsets.UTF_8));
    }

    public static SecretValue ofBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        return new SecretValue(value.clone());
    }

    /** Значение строкой. Вызывающий отвечает за то, чтобы строка не попала в журнал, метрику, событие или таблицу. */
    public String reveal() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Копия значения в виде байтов. */
    public byte[] revealBytes() {
        return bytes.clone();
    }

    /** Длина в байтах: по ней можно проверять, что секрет не пустой, не раскрывая его. */
    public int length() {
        return bytes.length;
    }

    /** Стирает значение в памяти, когда оно больше не нужно. */
    public void destroy() {
        Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public String toString() {
        return MASK;
    }

    /** Сравнение за постоянное время: время ответа не подсказывает, сколько первых байтов совпало. */
    @Override
    public boolean equals(Object other) {
        return other instanceof SecretValue that && MessageDigest.isEqual(bytes, that.bytes);
    }

    /** Постоянное значение: хеш значения подсказывал бы его в коллекциях и дампах. */
    @Override
    public int hashCode() {
        return 0;
    }
}
