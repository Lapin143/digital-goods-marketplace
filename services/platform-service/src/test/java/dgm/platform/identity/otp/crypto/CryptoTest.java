package dgm.platform.identity.otp.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.identity.otp.domain.OtpPurpose;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Хеши кодов и аудита с известными ответами: формула записана в Redis и в журнале аудита, её случайная смена оборвала бы проверку живых
 * кодов и сопоставимость хешей. Эталонные значения посчитаны независимо (Python, hmac и hashlib).
 */
class CryptoTest {

    private static final byte[] PEPPER = "test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIT_SECRET = "test-audit-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void codeHashMatchesTheReferenceValue() {
        assertEquals("6bbac3222564b4fab98c1b4bb3e3a035757bcf7667064c1778c72143f01f805d",
                OtpCrypto.ofBytes(PEPPER).codeHash(OtpPurpose.PHONE_CONFIRM, "user-1", "123456"));
    }

    @Test
    void targetMatchesTheReferenceValueAndLabelsSeparateNamespaces() {
        OtpCrypto crypto = OtpCrypto.ofBytes(PEPPER);

        assertEquals("38e328763cb2604833bcc6f6cb8fe8c225831d676ab27599e142820e03aabee2", crypto.target("phone", "+79001234567"));
        assertNotEquals(crypto.target("phone", "+79001234567"), crypto.target("ip", "+79001234567"));
    }

    @Test
    void anotherSecretGivesAnotherHash() {
        byte[] other = "another-pepper-0123456789abcdef!".getBytes(StandardCharsets.UTF_8);

        assertNotEquals(OtpCrypto.ofBytes(PEPPER).codeHash(OtpPurpose.SMS_LOGIN, "t", "123456"),
                OtpCrypto.ofBytes(other).codeHash(OtpPurpose.SMS_LOGIN, "t", "123456"));
    }

    @Test
    void shortSecretsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> OtpCrypto.ofBytes("short".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> AuditHasher.ofBytes("short".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void auditHashMatchesTheReferenceValue() {
        byte[] salt = new byte[16];
        for (int i = 0; i < 16; i++) {
            salt[i] = (byte) i;
        }

        assertEquals("e543a01d65a2c09943a0c35c05a7ef270db9405fd4cf18aee296f21bd50c2d56",
                AuditHasher.ofBytes(AUDIT_SECRET).hash(salt, "phone", "+79001234567"));
    }

    @Test
    void auditHashDependsOnSaltAndFieldAndDiesWithTheSalt() {
        AuditHasher hasher = AuditHasher.ofBytes(AUDIT_SECRET);
        byte[] saltA = new byte[16];
        byte[] saltB = new byte[16];
        saltB[0] = 1;

        assertNotEquals(hasher.hash(saltA, "phone", "+79001234567"), hasher.hash(saltB, "phone", "+79001234567"),
                "после удаления соли при анонимизации хеши прежних записей нельзя сопоставить");
        assertNotEquals(hasher.hash(saltA, "phone", "x"), hasher.hash(saltA, "ip", "x"));
        assertEquals(hasher.hash(saltA, "ip", "203.0.113.5"), hasher.hash(saltA, "ip", "203.0.113.5"));
    }

    @Test
    void secureCodesHaveSixDigitsWithLeadingZerosAndVary() {
        SecureCodeSource source = new SecureCodeSource();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 2000; i++) {
            String code = source.next();
            assertTrue(code.matches("[0-9]{6}"), code);
            seen.add(code);
        }

        assertTrue(seen.size() > 1500, "коды различаются: " + seen.size());
    }
}
