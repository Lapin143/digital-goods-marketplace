package dgm.catalog.sellers.integration.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.crypto.SellerSecrets;
import dgm.kit.secret.SecretValue;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Секрет подписи в виде строки для кабинета. Контрольные значения те же, что у {@code SellerSecretsTest} в {@code service-kit}, посчитанные
 * независимо на Python: один и тот же {@code sellerId} и версия дают один секрет в трёх сервисах (каталог, выдача, платформа), и это условие
 * того, что продавцу, которому секрет показали в кабинете, платформа подписывает именно им (ADR-028).
 */
@Tag("TC-959")
class IntegrationSecretsTest {

    private static final SecretValue MASTER = SecretValue.of("master-for-test-vectors-only");
    private static final UUID SELLER_A = UUID.fromString("5b7f0e1a-3c4d-4e5f-8a6b-7c8d9e0f1a2b");
    private static final UUID SELLER_B = UUID.fromString("9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a");
    private static final String A_V1 = "472be46cea9be46b54ed35049fe32e8bc331fe4560ed380ad16b13739f7324e6";
    private static final String A_V2 = "4893651dbeea4d5742f1d9e5e1451cd8e749ede1fc7b7e66769f91b63c547498";
    private static final String B_V1 = "4ae337e88a5d8f9e6ae114008d824d045e53968f69c5a09e195c3c6c8a9d1321";

    private final IntegrationSecrets secrets = new IntegrationSecrets(new SellerSecrets(MASTER));

    @Test
    void theValuesAreTheIndependentlyComputedVectorsOfTheKit() {
        assertEquals(A_V1, secrets.reveal(SELLER_A, 1));
        assertEquals(A_V2, secrets.reveal(SELLER_A, 2));
        assertEquals(B_V1, secrets.reveal(SELLER_B, 1));
    }

    @Test
    void theSecretIsSixtyFourLowercaseHexCharactersAndEveryInputChangesIt() {
        String value = secrets.reveal(SELLER_A, 1);
        assertTrue(value.matches("[0-9a-f]{64}"), value);
        assertEquals(value, secrets.reveal(SELLER_A, 1));
        assertNotEquals(value, secrets.reveal(SELLER_A, 2));
        assertNotEquals(value, secrets.reveal(SELLER_B, 1));
        assertNotEquals(value, new IntegrationSecrets(new SellerSecrets(SecretValue.of("another-master-secret-value"))).reveal(SELLER_A, 1));
    }

    @Test
    void theMasterSurvivesManyReadsBecauseOnlyTheDerivedCopyIsDestroyed() {
        for (int i = 0; i < 3; i++) {
            assertEquals(A_V1, secrets.reveal(SELLER_A, 1));
        }
    }

    @Test
    void aVersionBelowOneAndAMasterThatIsTooShortAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> secrets.reveal(SELLER_A, 0));
        assertThrows(IllegalArgumentException.class, () -> new SellerSecrets(SecretValue.of("short")));
    }
}
