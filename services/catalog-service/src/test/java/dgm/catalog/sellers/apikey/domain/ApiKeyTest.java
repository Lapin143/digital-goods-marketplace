package dgm.catalog.sellers.apikey.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.domain.FieldViolation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Ключ без значения, его статусы, имя при выпуске и результат выпуска, который не выдаёт значение при случайной записи в журнал. */
@Tag("TC-950")
@Tag("TC-951")
@Tag("TC-953")
class ApiKeyTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final UUID ID = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");

    private static ApiKey issued() {
        return ApiKey.issue(ID, SELLER, "Склад", "AAECAwQF", T0);
    }

    @Test
    void aNewKeyIsActiveAndHasNoRevocationDate() {
        ApiKey key = issued();
        assertTrue(key.active());
        assertEquals(ApiKeyStatus.ACTIVE, key.status());
        assertNull(key.revokedAt());
    }

    @Test
    void revocationMakesTheKeyRevokedWithTheDateAndKeepsEverythingElse() {
        ApiKey revoked = issued().revoke(T0.plusSeconds(60));
        assertFalse(revoked.active());
        assertEquals(ApiKeyStatus.REVOKED, revoked.status());
        assertEquals(T0.plusSeconds(60), revoked.revokedAt());
        assertEquals(ID, revoked.id());
        assertEquals(SELLER, revoked.sellerId());
        assertEquals("Склад", revoked.name());
        assertEquals("AAECAwQF", revoked.fingerprint());
        assertEquals(T0, revoked.createdAt());
    }

    @Test
    void aSecondRevocationChangesNothingNotEvenTheDate() {
        ApiKey revoked = issued().revoke(T0.plusSeconds(60));
        assertSame(revoked, revoked.revoke(T0.plusSeconds(3600)));
    }

    @Test
    void theRevocationDateExistsExactlyOnARevokedKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKey(ID, SELLER, "Склад", "AAECAwQF", ApiKeyStatus.ACTIVE, T0, T0));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKey(ID, SELLER, "Склад", "AAECAwQF", ApiKeyStatus.REVOKED, T0, null));
    }

    @Test
    void statusCodesMatchTheContractAndUnknownOnesAreNotStatuses() {
        assertEquals("active", ApiKeyStatus.ACTIVE.code());
        assertEquals("revoked", ApiKeyStatus.REVOKED.code());
        assertEquals(Optional.of(ApiKeyStatus.REVOKED), ApiKeyStatus.fromCode("revoked"));
        assertEquals(Optional.empty(), ApiKeyStatus.fromCode("deleted"));
        assertEquals(Optional.empty(), ApiKeyStatus.fromCode("ACTIVE"));
    }

    @Test
    void aValidNameHasNoViolationsAtTheBoundsOfOneAndSixtyCharacters() {
        assertEquals(List.of(), new ApiKeyForm("Я").violations());
        assertEquals(List.of(), new ApiKeyForm("а".repeat(60)).violations());
        // Знаки считаются по кодовым точкам, а не по символам UTF-16: шестьдесят эмодзи это 120 символов, но 60 знаков
        assertEquals(List.of(), new ApiKeyForm("😀".repeat(60)).violations());
    }

    @Test
    void anEmptyOrMissingNameIsRequired() {
        assertEquals(List.of(new FieldViolation("/name", "required", "Укажите имя ключа.")), new ApiKeyForm(null).violations());
        assertEquals("required", new ApiKeyForm("").violations().get(0).code());
    }

    @Test
    void aNameOfSixtyOneCharactersIsTooLong() {
        List<FieldViolation> violations = new ApiKeyForm("а".repeat(61)).violations();
        assertEquals(1, violations.size());
        assertEquals("/name", violations.get(0).pointer());
        assertEquals("too_long", violations.get(0).code());
        assertEquals("too_long", new ApiKeyForm("😀".repeat(61)).violations().get(0).code());
    }

    @Test
    void controlCharactersInTheNameAreRejectedBecauseTheNameGoesToListsAndLogs() {
        assertEquals("invalid_format", new ApiKeyForm("склад\nвторой").violations().get(0).code());
        assertEquals("invalid_format", new ApiKeyForm("склад\t").violations().get(0).code());
        assertEquals("invalid_format", new ApiKeyForm("склад\u0000").violations().get(0).code());
    }

    @Test
    void theIssuedKeyDoesNotShowTheValueWhenPrintedByAccident() {
        String value = ApiKeyValues.generate(length -> new byte[length]);
        String printed = new IssuedKey(issued(), value).toString();
        assertFalse(printed.contains(value), printed);
        assertFalse(printed.contains(value.substring(4)), printed);
        assertTrue(printed.contains("AAECAwQF"), printed);
    }
}
