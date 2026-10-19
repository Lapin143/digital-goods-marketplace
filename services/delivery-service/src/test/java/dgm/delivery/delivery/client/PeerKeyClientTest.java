package dgm.delivery.delivery.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.domain.ReleasedKeys;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент ключей (TC-605, OP-38): запрос {@code GET /internal/v1/keys?orderId=…}, разбор значений в {@code SecretValue}, отказы соседа как
 * коды ошибки попытки, один повтор запроса на чтение, ответ не по формату не принимается.
 */
@Tag("TC-605")
class PeerKeyClientTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final String KEYS = "{\"orderId\":\"" + ORDER + "\",\"keys\":[{\"keyId\":\"0199e0a0-0000-7000-8000-000000000801\",\"value\":\"AAAAA-11111\"},"
            + "{\"keyId\":\"0199e0a0-0000-7000-8000-000000000802\",\"value\":\"BBBBB-22222\"}]}";
    private static final String PROBLEM = "{\"type\":\"https://api.marketplace.example/problems/%s\",\"code\":\"%s\"}";

    private StubPeer peer;
    private PeerKeyClient client;

    @BeforeEach
    void start() throws IOException {
        peer = StubPeer.start(200, KEYS);
        client = new PeerKeyClient(() -> peer.client(PeerKeyClient.PEER, "300ms", Duration.ofMillis(500)));
    }

    @AfterEach
    void stop() {
        peer.stop();
    }

    private static String problem(String code) {
        return String.format(PROBLEM, code, code);
    }

    private static String codeOf(KeyRequestException e) {
        return e.code();
    }

    @Test
    void theKeysOfTheOrderAreRequestedByItsIdentifierAndReturnedInTheOrderOfIssue() {
        try (ReleasedKeys keys = client.keysOf(ORDER)) {
            assertEquals(2, keys.size());
            assertEquals("AAAAA-11111", keys.values().get(0).reveal());
            assertEquals("BBBBB-22222", keys.values().get(1).reveal());
            assertEquals(1, peer.seen().size());
            StubPeer.Seen seen = peer.seen().get(0);
            assertEquals("GET", seen.method());
            assertEquals("/internal/v1/keys", seen.path());
            assertEquals("orderId=" + ORDER, java.net.URI.create("http://x" + seen.path() + "?orderId=" + ORDER).getQuery());
        }
    }

    @Test
    void anOrderWithoutIssuedKeysAndAnUnknownOrderAreRefusalsWithTheirOwnCodes() {
        peer.then(409, problem("state-conflict"));
        peer.then(404, problem("not-found"));
        peer.then(403, problem("forbidden"));

        assertEquals("keys_not_issued", codeOf(assertThrows(KeyRequestException.class, () -> client.keysOf(ORDER))));
        assertEquals("keys_not_found", codeOf(assertThrows(KeyRequestException.class, () -> client.keysOf(ORDER))));
        assertEquals("inventory_rejected_403", codeOf(assertThrows(KeyRequestException.class, () -> client.keysOf(ORDER))));
        assertEquals(3, peer.seen().size(), "отказы по смыслу повторов не получают");
    }

    @Test
    void oneRetryAfterAServerErrorGivesTheKeysWithoutSpendingAnAttemptOfTheDelivery() {
        peer.then(503, "");

        try (ReleasedKeys keys = client.keysOf(ORDER)) {
            assertEquals(2, keys.size());
        }

        assertEquals(2, peer.seen().size());
    }

    @Test
    void twoServerErrorsInARowMeanTheInventoryServiceIsUnavailable() {
        peer.then(503, "");
        peer.then(502, "");

        KeyRequestException e = assertThrows(KeyRequestException.class, () -> client.keysOf(ORDER));

        assertEquals("inventory_unavailable", e.code());
        assertFalse(String.valueOf(e.getMessage()).contains("AAAAA"), "в тексте ошибки значений нет");
    }

    @Test
    void aSilentInventoryServiceIsUnavailableByTimeout() {
        peer.thenAfter(1500, 200, KEYS);
        peer.thenAfter(1500, 200, KEYS);

        assertEquals("inventory_unavailable", assertThrows(KeyRequestException.class, () -> client.keysOf(ORDER)).code());
    }

    @Test
    void anAnswerNotInTheFormatIsNotTrustedAndGivesNoPartialKeys() {
        String other = "{\"orderId\":\"0199e0a0-0000-7000-8000-000000000999\",\"keys\":[{\"keyId\":\"0199e0a0-0000-7000-8000-000000000801\",\"value\":\"AAAAA-11111\"}]}";
        String[] bodies = {"{", "[]", "{}", "{\"orderId\":\"" + ORDER + "\"}", "{\"orderId\":\"" + ORDER + "\",\"keys\":[]}",
            "{\"orderId\":\"" + ORDER + "\",\"keys\":[{\"keyId\":\"x\",\"value\":\"AAAAA-11111\"},{\"keyId\":\"y\"}]}",
            "{\"orderId\":\"" + ORDER + "\",\"keys\":[{\"keyId\":\"x\",\"value\":7}]}",
            "{\"orderId\":\"" + ORDER + "\",\"keys\":[{\"keyId\":\"x\",\"value\":\"\"}]}",
            "{\"orderId\":\"" + ORDER + "\",\"keys\":\"AAAAA-11111\"}", other};
        for (String body : bodies) {
            peer.then(200, body);

            KeyRequestException e = assertThrows(KeyRequestException.class, () -> client.keysOf(ORDER), body);

            assertEquals("keys_response_invalid", e.code(), body);
            assertTrue(e.getMessage() == null || !e.getMessage().contains("AAAAA"), "в тексте ошибки значений нет");
        }
    }
}
