package dgm.order.orders.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.client.DependencyUnavailableException;
import dgm.order.orders.client.CatalogClient.ProductCard;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент каталога на локальном HTTP-сервере: карточка читается строго, 404 это «товара нет», один повтор после 5xx, остальные отказы и ответы не по
 * формату считаются недоступностью каталога (SEQ-01, шаг 4).
 */
@Tag("TC-513")
class PeerCatalogClientTest {

    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final String CARD = "{\"productId\":\"" + PRODUCT + "\",\"sellerId\":\"" + SELLER + "\",\"title\":\"Лунная тропа: ключ Steam\","
            + "\"status\":\"published\",\"price\":{\"amount\":149900,\"currency\":\"RUB\"},\"issuanceMethod\":\"key_pool\",\"productType\":\"game_key\"}";

    private StubPeer peer;
    private PeerCatalogClient client;

    @BeforeEach
    void start() throws IOException {
        peer = StubPeer.start(200, CARD);
        client = new PeerCatalogClient(() -> peer.client(PeerCatalogClient.PEER, "200ms", Duration.ofMillis(400)));
    }

    @AfterEach
    void stop() {
        peer.stop();
    }

    @Test
    void theCardIsReadFromTheCatalogByTheInternalPath() {
        Optional<ProductCard> card = client.card(PRODUCT);

        assertEquals(Optional.of(new ProductCard(PRODUCT, SELLER, "Лунная тропа: ключ Steam", "published", 149_900, "RUB", "key_pool")), card);
        assertEquals(1, peer.seen().size());
        assertEquals("GET", peer.seen().get(0).method());
        assertEquals("/internal/v1/products/" + PRODUCT, peer.seen().get(0).path());
    }

    @Test
    void anUnknownProductIsAnEmptyAnswerWithoutARetry() {
        peer.then(404, "{\"type\":\"https://api.marketplace.example/problems/not-found\",\"code\":\"not-found\"}");

        assertEquals(Optional.empty(), client.card(PRODUCT));
        assertEquals(1, peer.seen().size());
    }

    @Test
    void oneRetryAfterAServerErrorIsMadeAndItsAnswerIsUsed() {
        peer.then(503, "");

        assertEquals(PRODUCT, client.card(PRODUCT).orElseThrow().productId());
        assertEquals(2, peer.seen().size());
    }

    @Test
    void twoServerErrorsInARowMeanTheCatalogIsUnavailable() {
        peer.then(503, "");
        peer.then(500, "");

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.card(PRODUCT));

        assertEquals(PeerCatalogClient.PEER, e.peer());
        assertEquals(DependencyUnavailableException.SERVER_ERROR, e.reason());
        assertEquals(2, peer.seen().size(), "попытка и один повтор");
    }

    @Test
    void aSilentCatalogMeansUnavailableByTimeout() {
        peer.thenAfter(1500, 200, CARD);
        peer.thenAfter(1500, 200, CARD);

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.card(PRODUCT));

        assertEquals(DependencyUnavailableException.TIMEOUT, e.reason());
    }

    @Test
    void aRefusalThatIsNot404IsAnUnavailableCatalogWithoutARetry() {
        peer.then(403, "{\"type\":\"https://api.marketplace.example/problems/forbidden\",\"code\":\"forbidden\"}");

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.card(PRODUCT));

        assertEquals(403, e.lastStatus());
        assertEquals(1, peer.seen().size());
    }

    @Test
    void anAnswerAboutAnotherProductOrNotInTheFormatIsNotTrusted() {
        String other = CARD.replace(PRODUCT.toString(), "0199e0a0-0000-7000-8000-000000000999");
        String noPrice = "{\"productId\":\"" + PRODUCT + "\",\"sellerId\":\"" + SELLER + "\",\"title\":\"x\",\"status\":\"published\",\"issuanceMethod\":\"key_pool\"}";
        String badId = CARD.replace(SELLER.toString(), "seller");
        for (String body : new String[] {other, noPrice, badId, "{", "[]", "{\"productId\":5}"}) {
            peer.then(200, body);

            assertThrows(DependencyUnavailableException.class, () -> client.card(PRODUCT), body);
        }
    }
}
