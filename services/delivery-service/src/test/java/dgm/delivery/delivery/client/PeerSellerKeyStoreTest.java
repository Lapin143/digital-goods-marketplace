package dgm.delivery.delivery.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.domain.KeyStoreResult;
import dgm.kit.json.Json;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент хранилища ключей продавца (TC-964, TC-967, OP-99): запрос {@code POST /internal/v1/seller-keys} с {@code Idempotency-Key} (UUID v4), один повтор
 * после сбоя с тем же ключом, отказ соседа по существу как {@code Refused}, недоступность и ответ не по формату как {@code Unavailable}, значения ключей
 * только в теле запроса.
 */
@Tag("TC-964")
@Tag("TC-967")
class PeerSellerKeyStoreTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID REQUEST = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final List<String> KEYS = List.of("AAAAA-11111", "BBBBB-22222");
    private static final String STORED = "{\"orderId\":\"" + ORDER + "\",\"stored\":2}";
    private static final String PROBLEM = "{\"type\":\"https://api.marketplace.example/problems/%s\",\"code\":\"%s\"}";
    private static final String UUID_V4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private StubPeer peer;
    private PeerSellerKeyStore store;

    @BeforeEach
    void start() throws IOException {
        peer = StubPeer.start(200, STORED);
        store = new PeerSellerKeyStore(() -> peer.client(PeerSellerKeyStore.PEER, "300ms", Duration.ofMillis(500)));
    }

    @AfterEach
    void stop() {
        peer.stop();
    }

    private static String problem(String code) {
        return String.format(PROBLEM, code, code);
    }

    @Test
    void theKeysGoToTheInventoryServiceInTheBodyWithTheOrderAndTheRequestAndComeBackAsStored() {
        KeyStoreResult result = store.store(ORDER, REQUEST, KEYS);

        assertEquals(new KeyStoreResult.Stored(2), result);
        assertEquals(1, peer.seen().size());
        StubPeer.Seen seen = peer.seen().get(0);
        assertEquals("POST", seen.method());
        assertEquals("/internal/v1/seller-keys", seen.path());
        Map<String, Object> body = Json.parseObject(seen.body());
        assertEquals(ORDER.toString(), body.get("orderId"));
        assertEquals(REQUEST.toString(), body.get("requestId"));
        assertEquals(KEYS, body.get("keys"));
        assertEquals(3, body.size(), "в теле только заказ, запрос и ключи");
    }

    @Test
    void theIdempotencyKeyIsAFreshUuidV4PerCallAndTheSameForTheRetryOfOneCall() {
        peer.then(503, "");

        store.store(ORDER, REQUEST, KEYS);
        store.store(ORDER, REQUEST, KEYS);

        assertEquals(3, peer.seen().size(), "первый вызов с одним повтором и второй вызов");
        String first = peer.seen().get(0).headers().get("idempotency-key");
        String retry = peer.seen().get(1).headers().get("idempotency-key");
        String second = peer.seen().get(2).headers().get("idempotency-key");
        assertTrue(first != null && first.matches(UUID_V4), "ключ идемпотентности это UUID v4: " + first);
        assertEquals(first, retry, "повтор того же вызова несёт тот же ключ");
        assertNotEquals(first, second, "новый вызов получает новый ключ");
    }

    @Test
    void oneRetryAfterAServerErrorStillStoresTheKeys() {
        peer.then(502, "");

        assertEquals(new KeyStoreResult.Stored(2), store.store(ORDER, REQUEST, KEYS));
        assertEquals(2, peer.seen().size());
    }

    @Test
    void aRefusalOfTheStateIsRefusedWithItsStatusAndNoRetry() {
        peer.then(409, problem("state-conflict"));

        KeyStoreResult result = store.store(ORDER, REQUEST, KEYS);

        KeyStoreResult.Refused refused = assertInstanceOf(KeyStoreResult.Refused.class, result);
        assertEquals(409, refused.status());
        assertTrue(refused.errors().isEmpty());
        assertEquals(1, peer.seen().size(), "отказ по смыслу повторов не получает");
    }

    @Test
    void aValidationRefusalCarriesOnlyThePositionsTheCodesAndTheTextsOfTheViolations() {
        peer.then(422, "{\"type\":\"https://api.marketplace.example/problems/validation-failed\",\"code\":\"validation-failed\",\"errors\":["
                + "{\"pointer\":\"/keys/1\",\"code\":\"not_unique\",\"detail\":\"Ключ уже есть.\",\"value\":\"BBBBB-22222\"},"
                + "{\"pointer\":\"/keys\",\"code\":\"out_of_range\"},"
                + "{\"pointer\":7,\"code\":\"x\"},"
                + "\"BBBBB-22222\"],\"trace\":\"AAAAA-11111\"}");

        KeyStoreResult.Refused refused = assertInstanceOf(KeyStoreResult.Refused.class, store.store(ORDER, REQUEST, KEYS));

        assertEquals(422, refused.status());
        assertEquals(List.of(Map.of("pointer", "/keys/1", "code", "not_unique", "detail", "Ключ уже есть."), Map.of("pointer", "/keys", "code", "out_of_range", "detail", PeerSellerKeyStore.MISSING_DETAIL)),
                refused.errors());
        assertFalse(refused.toString().contains("BBBBB"), "значений ключей в итоге нет");
        assertFalse(refused.toString().contains("AAAAA"), "значений ключей в итоге нет");
    }

    @Test
    void otherRefusalsOfTheNeighbourAreRefusedWithoutDetails() {
        peer.then(404, problem("not-found"));
        peer.then(403, problem("forbidden"));

        assertEquals(new KeyStoreResult.Refused(404, List.of()), store.store(ORDER, REQUEST, KEYS));
        assertEquals(new KeyStoreResult.Refused(403, List.of()), store.store(ORDER, REQUEST, KEYS));
    }

    @Test
    void twoServerErrorsInARowAndASilentNeighbourMeanUnavailable() {
        peer.then(503, "");
        peer.then(502, "");
        assertEquals(new KeyStoreResult.Unavailable("inventory_unavailable"), store.store(ORDER, REQUEST, KEYS));

        peer.thenAfter(1500, 200, STORED);
        peer.thenAfter(1500, 200, STORED);
        assertEquals(new KeyStoreResult.Unavailable("inventory_unavailable"), store.store(ORDER, REQUEST, KEYS));
    }

    @Test
    void anAnswerNotInTheFormatIsNotTakenForStored() {
        String[] bodies = {"{", "[]", "{}", "{\"orderId\":\"" + ORDER + "\"}", "{\"orderId\":\"" + ORDER + "\",\"stored\":\"2\"}",
            "{\"orderId\":\"" + ORDER + "\",\"stored\":1}",
            "{\"orderId\":\"0199e0a0-0000-7000-8000-000000000999\",\"stored\":2}"};
        for (String body : bodies) {
            peer.then(200, body);

            KeyStoreResult result = store.store(ORDER, REQUEST, KEYS);

            assertEquals(new KeyStoreResult.Unavailable("seller_keys_response_invalid"), result, body);
        }
    }
}
