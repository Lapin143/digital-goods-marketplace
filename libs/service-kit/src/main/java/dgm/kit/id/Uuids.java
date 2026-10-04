package dgm.kit.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** Идентификаторы UUID версии 7 (conventions.md, раздел 3): первые 48 бит это время в миллисекундах, поэтому они растут вместе со временем. */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {
    }

    public static UUID v7(Clock clock) {
        long millis = clock.millis();
        long high = (millis << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }

    /** Момент создания, записанный в идентификаторе версии 7. */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Нужен UUID версии 7, а версия " + uuid.version());
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
