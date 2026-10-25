package dgm.kit.config;

import java.time.Duration;

/**
 * Живое значение параметра платформы (правило 19 руководства среза 3). Читается без блокировок; значение и версия меняются одним
 * действием (один неизменяемый объект состояния), так что при записи новой версии не бывает промежутка с новым значением и старой
 * версией. До первого сообщения {@code config.changed} значением служит то, что задано в {@code application.yml} сервиса (версия 0).
 *
 * <p>Менять значение может только {@link ConfigRegistry#apply}: он проверяет версию, границы и INV-07.
 */
public final class ConfigValue {

    /** Значение с версией одним объектом. */
    private record State(long value, int version) {
    }

    private final String key;
    private final long defaultValue;
    private final long min;
    private final long max;
    private volatile State state;

    ConfigValue(String key, long defaultValue, long min, long max) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Ключ параметра не задан");
        }
        if (min > max) {
            throw new IllegalArgumentException("Границы параметра " + key + ": минимум " + min + " больше максимума " + max);
        }
        if (defaultValue < min || defaultValue > max) {
            throw new IllegalStateException("Значение " + key + " из настроек сервиса (" + defaultValue + ") вне границ " + min + " – " + max);
        }
        this.key = key;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.state = new State(defaultValue, 0);
    }

    public String key() {
        return key;
    }

    /** Текущее значение. */
    public long get() {
        return state.value();
    }

    /** Текущее значение как целое; границы параметра заведомо укладываются в {@code int}, иначе регистрация отказала бы раньше. */
    public int intValue() {
        return Math.toIntExact(state.value());
    }

    /** Текущее значение как срок в секундах. */
    public Duration seconds() {
        return Duration.ofSeconds(state.value());
    }

    /** Версия последнего применённого сообщения; 0, пока применено только значение из настроек. */
    public int version() {
        return state.version();
    }

    /** Значение из настроек сервиса, с которого значение начало работу. */
    public long defaultValue() {
        return defaultValue;
    }

    public long min() {
        return min;
    }

    public long max() {
        return max;
    }

    void set(long value, int version) {
        state = new State(value, version);
    }
}
