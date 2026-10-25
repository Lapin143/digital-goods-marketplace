package dgm.kit.config;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.InvalidEventException;

/**
 * Значение параметра из сообщения {@code config.changed} (AsyncAPI {@code ConfigChangedData}). Единица и автор правки слушателю не
 * нужны: сервис знает свои параметры и их единицы сам.
 *
 * @param key     ключ параметра; равен ключу записи Kafka и {@code subject} конверта
 * @param value   значение (все параметры R1 целые)
 * @param version версия параметра на платформе, растёт с каждой правкой
 */
public record ConfigChange(String key, long value, int version) {

    public static final String TYPE = "config.changed";
    /** Старшая версия схемы данных, которую понимает слушатель. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Разбирает значение записи сжатой темы.
     *
     * @throws InvalidEventException не конверт, не {@code config.changed}, схема новее известной, ключ расходится с {@code subject},
     *                               значение не целое или версия меньше 1
     */
    public static ConfigChange parse(String text) throws InvalidEventException {
        EventEnvelope envelope = EventEnvelope.parse(text);
        if (!TYPE.equals(envelope.type())) {
            throw new InvalidEventException("В теме параметров ожидается " + TYPE + ", пришёл " + envelope.type());
        }
        if (envelope.schemaVersion() > SCHEMA_VERSION) {
            throw new InvalidEventException("Версия схемы " + envelope.schemaVersion() + " новее известной " + SCHEMA_VERSION);
        }
        Object key = envelope.data().get("key");
        if (!(key instanceof String name) || name.isBlank() || !name.equals(envelope.subject())) {
            throw new InvalidEventException("Ключ параметра в данных не совпадает с subject конверта");
        }
        Object value = envelope.data().get("value");
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new InvalidEventException("Значение параметра " + name + " не целое число");
        }
        Object version = envelope.data().get("version");
        if (!(version instanceof Integer number) || number < 1) {
            throw new InvalidEventException("Версия параметра " + name + " не целое число от 1");
        }
        return new ConfigChange(name, ((Number) value).longValue(), number);
    }
}
