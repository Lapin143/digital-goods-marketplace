package dgm.inventory.inventory.keys;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.crypto.CryptoException;
import dgm.inventory.inventory.crypto.KekRing;
import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.keys.repository.DataKeyRepository;
import dgm.inventory.inventory.keys.service.DataKeys;
import dgm.inventory.inventory.keys.service.KekRotation;
import dgm.inventory.inventory.keys.service.KekStartup;
import dgm.kit.secret.SecretValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Ротация мастер-ключа (ADR-009, Р-174, TC-414): DEK перезавёртываются следующим мастер-ключом, значения ключей остаются как были и
 * читаются после ротации только новым мастер-ключом; повтор безвреден; сбой и чужая блокировка ничего не ломают; сервис не стартует,
 * если для какого-нибудь DEK нет мастер-ключа.
 */
@Tag("TC-414")
class KekRotationTest {

    private static final UUID KEY = UUID.fromString("0199e0a0-0000-7000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final String VALUE = "XXXXX-XXXXX-00001";

    private final SecureRandom random = new SecureRandom();
    private final KeyCipher oldKek = new KeyCipher(bytes(), random);
    private final KeyCipher newKek = new KeyCipher(bytes(), random);
    private FakePool pool;

    private byte[] bytes() {
        byte[] data = new byte[32];
        random.nextBytes(data);
        return data;
    }

    @BeforeEach
    void setUp() {
        pool = new FakePool();
    }

    /** Три DEK и одно значение, зашифрованное первым из них, под мастер-ключом первой версии. */
    private KeyCipher.Sealed seedUnderOldKek() {
        KekRing before = new KekRing(oldKek, 1, null);
        DataKeys keys = new DataKeys(pool.dataKeys, before);
        KeyCipher.Sealed sealed = oldKek.seal(keys.active().dek(), KEY, PRODUCT, SecretValue.of(VALUE));
        pool.dataKeys.insertActive(oldKek.wrap(oldKek.newDek()), 1);
        pool.dataKeys.insertActive(oldKek.wrap(oldKek.newDek()), 1);
        return sealed;
    }

    private KekRotation rotation(KekRing ring) {
        return new KekRotation(pool.dataKeys, ring, pool.transaction);
    }

    @Test
    void everyDekMovesToTheNextKekAndTheValuesStayReadableByTheNewKekAlone() {
        KeyCipher.Sealed sealed = seedUnderOldKek();
        byte[] wrappedBefore = pool.dataKeys.rows.get(0).wrapped().clone();

        KekRotation.Result result = rotation(new KekRing(oldKek, 1, newKek)).rotate();

        assertEquals(new KekRotation.Result(1, 2, 3, false), result);
        for (DataKeyRepository.Stored stored : pool.dataKeys.rows) {
            assertEquals(2, stored.kekVersion());
        }
        assertFalse(java.util.Arrays.equals(wrappedBefore, pool.dataKeys.rows.get(0).wrapped()), "обёртка DEK заменена");

        // После завершения оператор делает следующий мастер-ключ текущим и убирает старый: значения читаются без старого
        DataKeys afterSwap = new DataKeys(pool.dataKeys, new KekRing(newKek, 2, null));
        assertEquals(VALUE, newKek.open(afterSwap.byId(1), KEY, PRODUCT, sealed.nonce(), sealed.ciphertext()).reveal());
    }

    @Test
    void theDekItselfIsTheSameBeforeAndAfterRotation() {
        seedUnderOldKek();
        byte[] before = new DataKeys(pool.dataKeys, new KekRing(oldKek, 1, null)).byId(2);

        rotation(new KekRing(oldKek, 1, newKek)).rotate();

        assertArrayEquals(before, new DataKeys(pool.dataKeys, new KekRing(newKek, 2, null)).byId(2), "значения не перешифровываются: DEK прежний");
    }

    @Test
    void theOldKekAloneCannotOpenRotatedKeys() {
        seedUnderOldKek();
        rotation(new KekRing(oldKek, 1, newKek)).rotate();

        DataKeys oldOnly = new DataKeys(pool.dataKeys, new KekRing(oldKek, 1, null));
        CryptoException e = assertThrows(CryptoException.class, () -> oldOnly.byId(1));
        assertTrue(e.getMessage().contains("версии 2"), e.getMessage());
    }

    @Test
    void aSecondRotationFindsNothingToDo() {
        seedUnderOldKek();
        KekRotation rotation = rotation(new KekRing(oldKek, 1, newKek));
        rotation.rotate();

        assertEquals(new KekRotation.Result(1, 2, 0, false), rotation.rotate());
    }

    @Test
    void rotationWithoutTheNextKekIsRefused() {
        seedUnderOldKek();
        assertThrows(IllegalStateException.class, () -> rotation(new KekRing(oldKek, 1, null)).rotate());
        assertEquals(1, pool.dataKeys.rows.get(0).kekVersion());
    }

    @Test
    void anotherInstanceHoldingTheLockMeansNothingIsDone() {
        seedUnderOldKek();
        pool.dataKeys.rotationLockedByAnother = true;

        assertEquals(new KekRotation.Result(1, 2, 0, true), rotation(new KekRing(oldKek, 1, newKek)).rotate());
        assertEquals(1, pool.dataKeys.rows.get(0).kekVersion());
    }

    @Test
    void aFailureInTheMiddleSurfacesAndTheRetryFinishesTheJob() {
        seedUnderOldKek();
        pool.dataKeys.failOnRewrap = 2;
        KekRotation rotation = rotation(new KekRing(oldKek, 1, newKek));

        assertThrows(IllegalStateException.class, rotation::rotate);
        // В базе сбой откатывает транзакцию целиком; здесь память без отката, и повтор доводит оставшееся: перезаворачивание безопасно повторять
        KekRotation.Result retry = rotation.rotate();

        assertEquals(2, retry.rewrapped());
        for (DataKeyRepository.Stored stored : pool.dataKeys.rows) {
            assertEquals(2, stored.kekVersion());
        }
    }

    @Test
    void aNewDekCreatedDuringRotationIsWrappedByTheNextKek() {
        DataKeys keys = new DataKeys(pool.dataKeys, new KekRing(oldKek, 1, newKek));

        keys.active();

        assertEquals(2, pool.dataKeys.rows.get(0).kekVersion(), "иначе после смены ключа его пришлось бы перешифровывать ещё раз");
        assertNotEquals(0, pool.dataKeys.rows.get(0).wrapped().length);
    }

    @Test
    void theServiceDoesNotStartWhenSomeDekHasNoKek() {
        seedUnderOldKek();
        rotation(new KekRing(oldKek, 1, newKek)).rotate();
        // Оператор заменил секрет раньше времени: версия 1 снова текущая, а ключи уже версии 2
        KekRing wrong = new KekRing(oldKek, 1, null);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> startup(wrong, false).run(new DefaultApplicationArguments()));

        assertTrue(e.getMessage().contains("версии 2"), e.getMessage());
        assertFalse(e.getMessage().contains(VALUE));
    }

    @Test
    void startupWithRotateRewrapsCountsAndPassesTheCheck() {
        seedUnderOldKek();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        KekRing ring = new KekRing(oldKek, 1, newKek);

        new KekStartup(pool.dataKeys, ring, rotation(ring), pool.transaction, meters, true).run(new DefaultApplicationArguments());

        assertEquals(3.0, meters.get("kek_rotation_rewrapped_total").counter().count());
        assertEquals(2, pool.dataKeys.rows.get(2).kekVersion());
    }

    @Test
    void startupWithRotateButWithoutTheNextKekFails() {
        seedUnderOldKek();
        KekRing ring = new KekRing(oldKek, 1, null);

        assertThrows(IllegalStateException.class, () -> startup(ring, true).run(new DefaultApplicationArguments()));
    }

    @Test
    void startupWithoutRotationJustChecks() {
        seedUnderOldKek();
        KekRing ring = new KekRing(oldKek, 1, null);

        startup(ring, false).run(new DefaultApplicationArguments());

        assertEquals(1, pool.dataKeys.rows.get(0).kekVersion());
    }

    private KekStartup startup(KekRing ring, boolean rotate) {
        return new KekStartup(pool.dataKeys, ring, rotation(ring), pool.transaction, new SimpleMeterRegistry(), rotate);
    }
}
