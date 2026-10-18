package dgm.inventory.inventory.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.crypto.CryptoException;
import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.crypto.KeyValuesDocument;
import dgm.inventory.inventory.crypto.OpenedKey;
import dgm.inventory.inventory.keys.controller.KeyValuesController;
import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.keys.service.DataKeys;
import dgm.inventory.inventory.keys.service.KeyValuesService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.secret.SecretValue;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Значения ключей заказа ({@code getKeyValues}, ADR-009, U17): расшифровка в порядке выдачи, 404 без ключей, 409 пока оплата не
 * подтверждена, отказ при подмене шифртекста, разбор параметра, заголовки ответа. Сообщения исключений значений не содержат.
 */
class KeyValuesTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");

    /** Ключи заказа в памяти: шифртекст настоящий, статус задаёт тест. */
    private static final class StoredKeys implements KeyRepository {
        final List<StoredKey> rows = new ArrayList<>();

        @Override
        public Map<String, Integer> countByStatus(UUID productId) {
            return Map.of();
        }

        @Override
        public List<UUID> reserveFree(UUID productId, int quantity, UUID orderId, UUID reservationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int releaseByReservation(UUID reservationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int issueByReservation(UUID reservationId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredKey> byOrder(UUID orderId) {
            return orderId.equals(ORDER) ? List.copyOf(rows) : List.of();
        }

        @Override
        public Set<String> insertNew(UUID productId, List<NewKey> keys) {
            throw new UnsupportedOperationException();
        }
    }

    private KeyCipher cipher;
    private DataKeys.Active dek;
    private StoredKeys stored;
    private KeyValuesService service;

    @BeforeEach
    void setUp() {
        cipher = new KeyCipher(new byte[32], new SecureRandom());
        FakePool pool = new FakePool();
        DataKeys dataKeys = new DataKeys(pool.dataKeys, cipher);
        dek = dataKeys.active();
        stored = new StoredKeys();
        service = new KeyValuesService(stored, dataKeys, cipher);
    }

    private UUID add(String value, String status, UUID sealedForProduct) {
        UUID keyId = UUID.randomUUID();
        KeyCipher.Sealed sealed = cipher.seal(dek.dek(), keyId, sealedForProduct, SecretValue.of(value));
        stored.rows.add(new KeyRepository.StoredKey(keyId, PRODUCT, status, dek.id(), sealed.nonce(), sealed.ciphertext()));
        return keyId;
    }

    @Test
    void valuesAreDecryptedInTheOrderOfIssue() {
        UUID first = add("AAAAA-BBBBB-00001", "issued", PRODUCT);
        UUID second = add("AAAAA-BBBBB-00002", "issued", PRODUCT);
        List<OpenedKey> values = service.valuesOf(ORDER);
        assertEquals(List.of(first, second), values.stream().map(OpenedKey::keyId).toList());
        assertEquals("AAAAA-BBBBB-00001", values.get(0).value().reveal());
        assertEquals("AAAAA-BBBBB-00002", values.get(1).value().reveal());
        KeyValuesService.destroy(values);
    }

    @Test
    void anOrderWithoutKeysIs404() {
        ProblemException e = assertThrows(ProblemException.class, () -> service.valuesOf(UUID.randomUUID()));
        assertEquals(ProblemType.NOT_FOUND, e.type());
    }

    @Test
    void keysThatAreOnlyReservedAreNotGivenOutBeforeThePaymentIsConfirmed() {
        add("SECRET-RESERVED-0001", "reserved", PRODUCT);
        ProblemException e = assertThrows(ProblemException.class, () -> service.valuesOf(ORDER));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("reserved", e.extensions().get("currentStatus"));
        assertFalse(String.valueOf(e.getMessage()).contains("SECRET-RESERVED-0001"));
    }

    @Test
    void aCiphertextMovedToAnotherProductDoesNotDecryptAndTheErrorCarriesNoValue() {
        add("SECRET-MOVED-0001", "issued", UUID.randomUUID());
        CryptoException e = assertThrows(CryptoException.class, () -> service.valuesOf(ORDER));
        assertFalse(String.valueOf(e.getMessage()).contains("SECRET-MOVED-0001"));
    }

    @Test
    void theDocumentFollowsTheKeyValuesSchema() {
        UUID key = add("AAAAA-BBBBB-00001", "issued", PRODUCT);
        List<OpenedKey> values = service.valuesOf(ORDER);
        Map<String, Object> document = Json.parseObject(KeyValuesDocument.json(ORDER, values));
        KeyValuesService.destroy(values);
        assertEquals(Set.of("orderId", "keys"), document.keySet());
        assertEquals(ORDER.toString(), document.get("orderId"));
        List<?> keys = (List<?>) document.get("keys");
        assertEquals(1, keys.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) keys.get(0);
        assertEquals(Map.of("keyId", key.toString(), "value", "AAAAA-BBBBB-00001"), item);
    }

    @Test
    void theControllerWritesTheBodyStraightToTheResponseWithNoStoreAndRefusesAMissingOrMalformedOrderId() throws Exception {
        add("AAAAA-BBBBB-00001", "issued", PRODUCT);
        KeyValuesController controller = new KeyValuesController(service);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.values(ORDER.toString(), response);
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(String.valueOf(response.getContentType()).startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("AAAAA-BBBBB-00001"));
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> controller.values(null, new MockHttpServletResponse())).type());
        assertEquals(ProblemType.BAD_REQUEST,
                assertThrows(ProblemException.class, () -> controller.values("не-идентификатор", new MockHttpServletResponse())).type());
        assertEquals(ProblemType.BAD_REQUEST,
                assertThrows(ProblemException.class, () -> controller.values(ORDER.toString().toUpperCase(), new MockHttpServletResponse())).type());
    }
}
