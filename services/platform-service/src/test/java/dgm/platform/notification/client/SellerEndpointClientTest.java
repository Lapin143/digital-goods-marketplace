package dgm.platform.notification.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.domain.SellerEndpoint;
import dgm.platform.notification.testing.StubPeer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент подключений продавцов (TC-973, OP-97): запрос {@code GET /internal/v1/sellers/{sellerId}/integration}, память об ответе на 30 секунд, после
 * которой смена секрета и отключение действуют, отсутствие подключения не запоминается, ответ не по формату не принимается, адрес в тексте ошибок не
 * печатается.
 */
@Tag("TC-973")
class SellerEndpointClientTest {

    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID OTHER = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final String URL = "https://shop.seller.example/dgm/webhook?token=SECRET-IN-ADDRESS";

    private StubPeer peer;
    private ManualClock clock;
    private SellerEndpointClient client;

    @BeforeEach
    void start() throws IOException {
        peer = StubPeer.start(200, answer(SELLER, "active", 1));
        clock = new ManualClock(NOW);
        client = new SellerEndpointClient(() -> peer.client(SellerEndpointClient.PEER, "300ms", Duration.ofMillis(500)), clock);
    }

    @AfterEach
    void stop() {
        peer.stop();
    }

    private static String answer(UUID seller, String status, int secretVersion) {
        return "{\"sellerId\":\"" + seller + "\",\"endpointUrl\":\"" + URL + "\",\"status\":\"" + status + "\",\"secretVersion\":" + secretVersion + "}";
    }

    @Test
    void theConnectionIsRequestedByTheIdentifierOfTheSellerAndReadIntoTheAddressTheStatusAndTheVersionOfTheSecret() {
        Optional<SellerEndpoint> found = client.find(SELLER);

        assertTrue(found.isPresent());
        assertEquals(URL, found.get().url());
        assertEquals("active", found.get().status());
        assertTrue(found.get().active());
        assertEquals(1, found.get().secretVersion());
        assertEquals(1, peer.seen().size());
        assertEquals("GET", peer.seen().get(0).method());
        assertEquals("/internal/v1/sellers/" + SELLER + "/integration", peer.seen().get(0).path());
    }

    @Test
    void aBatchForOneSellerIsServedFromMemoryWithoutCallingTheCatalog() {
        for (int i = 0; i < 50; i++) {
            assertTrue(client.find(SELLER).isPresent());
        }

        assertEquals(1, peer.seen().size());
    }

    @Test
    void memoryIsPerSeller() {
        peer.then(200, answer(SELLER, "active", 1));
        peer.then(200, answer(OTHER, "active", 3));

        assertEquals(1, client.find(SELLER).orElseThrow().secretVersion());
        assertEquals(3, client.find(OTHER).orElseThrow().secretVersion());
        assertEquals(2, peer.seen().size());
    }

    @Test
    void aChangedSecretAndADisabledConnectionTakeEffectWhenTheMemoryExpires() {
        peer.then(200, answer(SELLER, "active", 1));
        peer.then(200, answer(SELLER, "active", 2));
        peer.then(200, answer(SELLER, "disabled", 2));

        assertEquals(1, client.find(SELLER).orElseThrow().secretVersion());
        clock.advance(SellerEndpointClient.MEMORY.minusSeconds(1));
        assertEquals(1, client.find(SELLER).orElseThrow().secretVersion(), "до конца срока памяти прежний ответ");
        assertEquals(1, peer.seen().size());

        clock.advance(Duration.ofSeconds(1));
        assertEquals(2, client.find(SELLER).orElseThrow().secretVersion(), "срок вышел: новая версия секрета");
        clock.advance(SellerEndpointClient.MEMORY);
        SellerEndpoint disabled = client.find(SELLER).orElseThrow();
        assertFalse(disabled.active(), "отключённое подключение отправитель пропускает");
        assertEquals("disabled", disabled.status());
        assertEquals(3, peer.seen().size());
    }

    @Test
    void theMemoryIsThirtySeconds() {
        assertEquals(Duration.ofSeconds(30), SellerEndpointClient.MEMORY);
    }

    @Test
    void noConnectionIsAnEmptyAnswerThatIsNotRememberedSoANewConnectionIsSeenAtOnce() {
        peer.then(404, "{\"type\":\"https://api.marketplace.example/problems/not-found\",\"code\":\"not-found\"}");
        peer.then(200, answer(SELLER, "active", 1));

        assertTrue(client.find(SELLER).isEmpty());
        assertTrue(client.find(SELLER).isPresent(), "отсутствие подключения не запоминается");
        assertEquals(2, peer.seen().size());
    }

    @Test
    void aRefusalOfTheCatalogIsPassedOnAndForgetsTheOldAnswer() {
        peer.then(200, answer(SELLER, "active", 1));
        peer.then(403, "{\"code\":\"forbidden\"}");

        assertTrue(client.find(SELLER).isPresent());
        clock.advance(SellerEndpointClient.MEMORY);

        DependencyRejectedException e = assertThrows(DependencyRejectedException.class, () -> client.find(SELLER));
        assertEquals(403, e.status());
    }

    @Test
    void anUnavailableCatalogIsATemporaryFailureForTheCaller() {
        peer.then(503, "");
        peer.then(502, "");

        assertThrows(DependencyUnavailableException.class, () -> client.find(SELLER));
    }

    @Test
    void anAnswerNotInTheFormatIsNotTrustedAndNotRemembered() {
        String[] bodies = {"{", "[]", "{}", answer(OTHER, "active", 1), answer(SELLER, "active", 0),
            "{\"sellerId\":\"" + SELLER + "\",\"endpointUrl\":null,\"status\":\"active\",\"secretVersion\":1}",
            "{\"sellerId\":\"" + SELLER + "\",\"endpointUrl\":\"" + URL + "\",\"status\":7,\"secretVersion\":1}",
            "{\"sellerId\":\"" + SELLER + "\",\"endpointUrl\":\"" + URL + "\",\"status\":\"active\",\"secretVersion\":\"1\"}"};
        for (String body : bodies) {
            peer.then(200, body);

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.find(SELLER), body);

            assertFalse(String.valueOf(e.getMessage()).contains("SECRET-IN-ADDRESS"), "адрес в тексте ошибки не печатается");
        }
        assertEquals(bodies.length, peer.seen().size(), "ответ не по формату не запомнен: каждый запрос дошёл до каталога");
    }

    @Test
    void theAddressIsNotPrintedInTheStringFormOfTheConnection() {
        SellerEndpoint endpoint = client.find(SELLER).orElseThrow();

        assertFalse(endpoint.toString().contains("SECRET-IN-ADDRESS"));
        assertFalse(endpoint.toString().contains("shop.seller.example"));
        assertTrue(endpoint.toString().contains("secretVersion=1"));
    }
}
