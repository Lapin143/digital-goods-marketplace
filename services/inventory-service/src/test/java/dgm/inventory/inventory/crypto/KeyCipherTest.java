package dgm.inventory.inventory.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Шифрование значений ключей (ADR-009): цикл, AAD, nonce, мастер-ключ, обёртка DEK, сообщения без значений. */
class KeyCipherTest {

    private static final UUID KEY = UUID.fromString("0199e0a0-0000-7000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final String VALUE = "XXXXX-XXXXX-00001";

    private final SecureRandom random = new SecureRandom();
    private final KeyCipher cipher = new KeyCipher(bytes(32), random);

    private byte[] bytes(int length) {
        byte[] data = new byte[length];
        random.nextBytes(data);
        return data;
    }

    @Test
    void sealedValueOpensBackToTheSameValue() {
        byte[] dek = cipher.newDek();
        KeyCipher.Sealed sealed = cipher.seal(dek, KEY, PRODUCT, SecretValue.of(VALUE));
        assertEquals(12, sealed.nonce().length, "nonce 96 бит");
        assertEquals(VALUE.length() + 16, sealed.ciphertext().length, "шифртекст длиной с значение плюс тег 16 байт");
        assertEquals(VALUE, cipher.open(dek, KEY, PRODUCT, sealed.nonce(), sealed.ciphertext()).reveal());
    }

    @Test
    void ciphertextDoesNotContainThePlainValue() {
        KeyCipher.Sealed sealed = cipher.seal(cipher.newDek(), KEY, PRODUCT, SecretValue.of(VALUE));
        assertFalse(new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1).contains(VALUE));
    }

    @Test
    void theSameValueGetsADifferentNonceAndCiphertextEveryTime() {
        byte[] dek = cipher.newDek();
        KeyCipher.Sealed first = cipher.seal(dek, KEY, PRODUCT, SecretValue.of(VALUE));
        KeyCipher.Sealed second = cipher.seal(dek, KEY, PRODUCT, SecretValue.of(VALUE));
        assertFalse(Arrays.equals(first.nonce(), second.nonce()));
        assertFalse(Arrays.equals(first.ciphertext(), second.ciphertext()));
    }

    @Test
    void aSubstitutedKeyIdOrProductFailsTheIntegrityCheck() {
        byte[] dek = cipher.newDek();
        KeyCipher.Sealed sealed = cipher.seal(dek, KEY, PRODUCT, SecretValue.of(VALUE));
        assertThrows(CryptoException.class, () -> cipher.open(dek, UUID.randomUUID(), PRODUCT, sealed.nonce(), sealed.ciphertext()),
                "шифртекст, перенесённый в другую строку");
        assertThrows(CryptoException.class, () -> cipher.open(dek, KEY, UUID.randomUUID(), sealed.nonce(), sealed.ciphertext()),
                "шифртекст, перенесённый в другой товар");
    }

    @Test
    void aTamperedCiphertextOrTheWrongDekIsRefused() {
        byte[] dek = cipher.newDek();
        KeyCipher.Sealed sealed = cipher.seal(dek, KEY, PRODUCT, SecretValue.of(VALUE));
        byte[] tampered = sealed.ciphertext().clone();
        tampered[0] ^= 1;
        assertThrows(CryptoException.class, () -> cipher.open(dek, KEY, PRODUCT, sealed.nonce(), tampered));
        assertThrows(CryptoException.class, () -> cipher.open(cipher.newDek(), KEY, PRODUCT, sealed.nonce(), sealed.ciphertext()));
        assertThrows(CryptoException.class, () -> cipher.open(dek, KEY, PRODUCT, new byte[11], sealed.ciphertext()));
    }

    @Test
    void theDataKeyIsWrappedAndUnwrappedByTheMasterKey() {
        byte[] dek = cipher.newDek();
        byte[] wrapped = cipher.wrap(dek);
        assertEquals(KeyCipher.WRAPPED_BYTES, wrapped.length, "60 байт: ограничение ck_data_key_wrapped_key");
        assertArrayEquals(dek, cipher.unwrap(wrapped));
        assertFalse(Arrays.equals(wrapped, cipher.wrap(dek)), "каждая обёртка со своим nonce");
    }

    @Test
    void theWrongMasterKeyDoesNotUnwrapTheDataKey() {
        byte[] wrapped = cipher.wrap(cipher.newDek());
        KeyCipher other = new KeyCipher(bytes(32), random);
        assertThrows(CryptoException.class, () -> other.unwrap(wrapped));
        assertThrows(CryptoException.class, () -> cipher.unwrap(new byte[10]));
    }

    @Test
    void theMasterKeyComesFromABase64SecretOf32Bytes() {
        byte[] kek = bytes(32);
        KeyCipher fromSecret = KeyCipher.fromSecret(SecretValue.of(Base64.getEncoder().encodeToString(kek)), random);
        byte[] dek = cipher.newDek();
        assertArrayEquals(dek, fromSecret.unwrap(new KeyCipher(kek, random).wrap(dek)));
        assertThrows(IllegalStateException.class, () -> KeyCipher.fromSecret(SecretValue.of(Base64.getEncoder().encodeToString(bytes(16))), random));
        assertThrows(IllegalStateException.class, () -> KeyCipher.fromSecret(SecretValue.of("не base64 !!!"), random));
        assertThrows(IllegalArgumentException.class, () -> new KeyCipher(bytes(31), random));
    }

    @Test
    void errorMessagesNeverContainTheValue() {
        byte[] dek = cipher.newDek();
        KeyCipher.Sealed sealed = cipher.seal(dek, KEY, PRODUCT, SecretValue.of(VALUE));
        CryptoException e = assertThrows(CryptoException.class, () -> cipher.open(dek, UUID.randomUUID(), PRODUCT, sealed.nonce(), sealed.ciphertext()));
        assertFalse(e.getMessage().contains(VALUE));
        assertTrue(e.getMessage().contains("расшифровать"));
        assertNotEquals("", SecretValue.of(VALUE).toString());
        assertFalse(SecretValue.of(VALUE).toString().contains(VALUE), "toString значения замаскирован");
    }
}
