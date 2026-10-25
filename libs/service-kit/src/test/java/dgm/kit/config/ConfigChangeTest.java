package dgm.kit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.events.InvalidEventException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Разбор записи темы {@code platform.config}: что слушатель принимает и что пропускает как неисправное. */
@Tag("TC-808")
class ConfigChangeTest {

    private static final String KEY = "reservation.ttl-seconds";

    @Test
    void parsesTheValueAndTheVersionOfAConfigChangedEvent() throws Exception {
        ConfigChange change = ConfigChange.parse(ConfigMessages.changed(KEY, 1200, 4));
        assertEquals(new ConfigChange(KEY, 1200, 4), change);
    }

    @Test
    void acceptsAValueBeyondTheIntRange() throws Exception {
        assertEquals(5_000_000_000L, ConfigChange.parse(ConfigMessages.changed(KEY, 5_000_000_000L, 2)).value());
    }

    private static void rejects(String text) {
        assertThrows(InvalidEventException.class, () -> ConfigChange.parse(text), text);
    }

    @Test
    void rejectsWhatIsNotAConfigChangedEventForTheKey() {
        rejects(null);
        rejects("");
        rejects("not json");
        rejects(ConfigMessages.changed(KEY, KEY, "seller.approved", 1, "\"value\":1,\"version\":1"));
        rejects(ConfigMessages.changed("other.ttl-seconds", KEY, "config.changed", 1, "\"value\":1,\"version\":1"));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 2, "\"value\":1,\"version\":1"));
    }

    @Test
    void rejectsAValueOrAVersionThatIsNotAnInteger() {
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"value\":\"900\",\"version\":1"));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"value\":900.5,\"version\":1"));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"value\":null,\"version\":1"));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"value\":true,\"version\":1"));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"value\":900,\"version\":0"));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"value\":900,\"version\":\"2\""));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"value\":900"));
        rejects(ConfigMessages.changed(KEY, KEY, "config.changed", 1, "\"version\":2"));
    }
}
