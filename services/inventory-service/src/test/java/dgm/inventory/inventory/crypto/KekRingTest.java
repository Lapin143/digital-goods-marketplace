package dgm.inventory.inventory.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Набор известных мастер-ключей (ADR-009, Р-174): какой ключ открывает DEK какой версии и каким заворачивается новый DEK. */
@Tag("TC-414")
class KekRingTest {

    private final SecureRandom random = new SecureRandom();
    private final KeyCipher current = new KeyCipher(new byte[32], random);
    private final KeyCipher next = new KeyCipher(new byte[32], random);

    @Test
    void withoutRotationOnlyTheCurrentKekIsKnownAndWraps() {
        KekRing ring = new KekRing(current, 3, null);
        assertFalse(ring.rotating());
        assertTrue(ring.knows(3));
        assertFalse(ring.knows(4));
        assertSame(current, ring.forVersion(3));
        assertSame(current, ring.newest());
        assertEquals(3, ring.newestVersion());
        assertThrows(IllegalStateException.class, ring::next);
    }

    @Test
    void duringRotationBothKeysAreKnownAndTheNewOneWraps() {
        KekRing ring = new KekRing(current, 1, next);
        assertTrue(ring.rotating());
        assertTrue(ring.knows(1));
        assertTrue(ring.knows(2));
        assertFalse(ring.knows(3));
        assertSame(next, ring.forVersion(2));
        assertSame(next, ring.newest());
        assertEquals(2, ring.newestVersion());
        assertEquals(2, ring.nextVersion());
    }

    @Test
    void anUnknownVersionIsRefusedWithoutDetails() {
        CryptoException e = assertThrows(CryptoException.class, () -> KekRing.single(current).forVersion(2));
        assertTrue(e.getMessage().contains("версии 2"));
    }

    @Test
    void theSingleRingStartsAtVersionOne() {
        assertEquals(1, KekRing.single(current).currentVersion());
    }

    @Test
    void versionBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new KekRing(current, 0, null));
    }
}
