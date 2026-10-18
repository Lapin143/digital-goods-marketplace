package dgm.kit.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.id.Uuids;
import dgm.kit.time.ManualClock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ключи шагов саги (Р-149): UUID версии 4, который принимает фильтр, и детерминированный. */
class IdempotencyKeysTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");

    @Test
    void theKeyIsAVersion4UuidThatTheFilterAccepts() {
        UUID key = IdempotencyKeys.derived("reserve", ORDER);
        assertEquals(4, key.version());
        assertEquals(2, key.variant());
        assertNotNull(IdempotencyRequest.parseKey(key.toString()), "фильтр идемпотентности ключ принимает");
        assertNotNull(IdempotencyRequest.parseKey(IdempotencyKeys.derived("open-session", UUID.randomUUID()).toString()));
    }

    @Test
    void theOrderIdItselfIsNotAValidKeyBecauseItIsVersion7() {
        ManualClock clock = new ManualClock(Instant.parse("2026-10-18T12:00:00Z"));
        UUID orderId = Uuids.v7(clock);
        assertEquals(7, orderId.version());
        assertNull(IdempotencyRequest.parseKey(orderId.toString()));
        assertNotNull(IdempotencyRequest.parseKey(IdempotencyKeys.derived("reserve", orderId).toString()));
    }

    @Test
    void theSameStepOfTheSameOrderGivesTheSameKey() {
        assertEquals(IdempotencyKeys.derived("confirm", ORDER), IdempotencyKeys.derived("confirm", ORDER));
    }

    @Test
    void stepsAndOrdersDoNotCollide() {
        Set<UUID> keys = new HashSet<>();
        for (String step : new String[] {"reserve", "confirm", "open-session", "release"}) {
            keys.add(IdempotencyKeys.derived(step, ORDER));
            keys.add(IdempotencyKeys.derived(step, UUID.fromString("0199e0a0-0000-7000-8000-000000000302")));
        }
        assertEquals(8, keys.size());
        assertNotEquals(IdempotencyKeys.derived("reserve", ORDER), ORDER);
    }

    @Test
    void anEmptyStepIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKeys.derived(" ", ORDER));
        assertThrows(IllegalArgumentException.class, () -> IdempotencyKeys.derived(null, ORDER));
    }
}
