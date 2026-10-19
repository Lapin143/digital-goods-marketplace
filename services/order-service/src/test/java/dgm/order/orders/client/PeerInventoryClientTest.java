package dgm.order.orders.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.idempotency.IdempotencyKeys;
import dgm.kit.json.Json;
import dgm.order.orders.client.InventoryClient.OutOfStock;
import dgm.order.orders.client.InventoryClient.ReserveResult;
import dgm.order.orders.client.InventoryClient.Reserved;
import dgm.order.orders.client.InventoryClient.UnknownProduct;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент сервиса остатков на локальном HTTP-сервере: тело и ключ идемпотентности запроса (ключ выводится из шага и заказа, Р-149, и не меняется при
 * повторе), исходы «резерв», «ключей меньше» и «товара нет», один повтор после 5xx, остальные отказы и ответы не по формату это недоступность.
 */
@Tag("TC-513")
class PeerInventoryClientTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final UUID RESERVATION = UUID.fromString("0199e0a0-0000-7000-8000-000000000501");
    private static final Instant END = Instant.parse("2026-10-18T12:15:00.123Z");
    private static final String OK = "{\"reservationId\":\"" + RESERVATION + "\",\"orderId\":\"" + ORDER + "\",\"productId\":\"" + PRODUCT
            + "\",\"quantity\":2,\"status\":\"active\",\"expiresAt\":\"2026-10-18T12:15:00.123Z\"}";
    private static final String PROBLEM = "{\"type\":\"https://api.marketplace.example/problems/%s\",\"code\":\"%s\",\"available\":%d}";

    private StubPeer peer;
    private PeerInventoryClient client;

    @BeforeEach
    void start() throws IOException {
        peer = StubPeer.start(201, OK);
        client = new PeerInventoryClient(() -> peer.client(PeerInventoryClient.PEER, "300ms", Duration.ofMillis(500)));
    }

    @AfterEach
    void stop() {
        peer.stop();
    }

    private static String problem(String code, int available) {
        return String.format(PROBLEM, code, code, available);
    }

    @Test
    void theReservationIsRequestedWithTheOrderTheProductAndTheQuantityAndADerivedKey() {
        ReserveResult result = client.reserve(ORDER, PRODUCT, 2);

        assertEquals(new Reserved(RESERVATION, END), result);
        assertEquals(1, peer.seen().size());
        StubPeer.Seen seen = peer.seen().get(0);
        assertEquals("POST", seen.method());
        assertEquals("/internal/v1/reservations", seen.path());
        assertEquals(Map.of("orderId", ORDER.toString(), "productId", PRODUCT.toString(), "quantity", 2), Json.parseObject(seen.body()));
        assertEquals(IdempotencyKeys.derived("reserve", ORDER).toString(), seen.headers().get("idempotency-key"));
        assertEquals("application/json", seen.headers().get("content-type"));
    }

    @Test
    void theKeyDependsOnTheStepAndTheOrderOnly() {
        client.reserve(ORDER, PRODUCT, 2);
        client.reserve(ORDER, PRODUCT, 2);
        client.reserve(UUID.fromString("0199e0a0-0000-7000-8000-000000000302"), PRODUCT, 2);

        String first = peer.seen().get(0).headers().get("idempotency-key");
        assertEquals(first, peer.seen().get(1).headers().get("idempotency-key"), "повтор шага несёт тот же ключ");
        assertEquals(false, first.equals(peer.seen().get(2).headers().get("idempotency-key")), "другой заказ другой ключ");
    }

    @Test
    void notEnoughKeysIsAnOutcomeWithTheNumberOfFreeKeys() {
        peer.then(409, problem("insufficient-stock", 1));

        assertEquals(new OutOfStock(1), client.reserve(ORDER, PRODUCT, 2));
        assertEquals(1, peer.seen().size(), "отказ по смыслу не повторяется");
    }

    @Test
    void aMissingNumberOfFreeKeysIsTreatedAsZero() {
        peer.then(409, "{\"type\":\"https://api.marketplace.example/problems/insufficient-stock\"}");

        assertEquals(new OutOfStock(0), client.reserve(ORDER, PRODUCT, 2));
    }

    @Test
    void anUnknownOrUnavailableProductIsAnOutcome() {
        peer.then(404, problem("not-found", 0));
        peer.then(409, problem("product-unavailable", 0));

        assertEquals(new UnknownProduct(), client.reserve(ORDER, PRODUCT, 2));
        assertEquals(new UnknownProduct(), client.reserve(ORDER, PRODUCT, 2));
    }

    @Test
    void aRetryAfterAServerErrorCarriesTheSameKeyAndItsAnswerIsUsed() {
        peer.then(503, "");

        assertEquals(new Reserved(RESERVATION, END), client.reserve(ORDER, PRODUCT, 2));

        assertEquals(2, peer.seen().size());
        assertEquals(peer.seen().get(0).headers().get("idempotency-key"), peer.seen().get(1).headers().get("idempotency-key"));
    }

    @Test
    void twoServerErrorsInARowMeanTheInventoryServiceIsUnavailable() {
        peer.then(503, "");
        peer.then(502, "");

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.reserve(ORDER, PRODUCT, 2));

        assertEquals(PeerInventoryClient.PEER, e.peer());
        assertEquals(2, peer.seen().size());
    }

    @Test
    void aSilentInventoryServiceMeansUnavailableByTimeout() {
        peer.thenAfter(1500, 201, OK);
        peer.thenAfter(1500, 201, OK);

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.reserve(ORDER, PRODUCT, 2));

        assertEquals(DependencyUnavailableException.TIMEOUT, e.reason());
    }

    @Test
    void anyOtherRefusalIsUnavailableWithoutARetry() {
        for (int status : new int[] {400, 403, 409, 422}) {
            peer.then(status, status == 409 ? problem("state-conflict", 0) : "");

            DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.reserve(ORDER, PRODUCT, 2), "статус " + status);

            assertEquals(status, e.lastStatus());
        }
        assertEquals(4, peer.seen().size());
    }

    @Test
    void anAnswerNotInTheFormatIsNotTrusted() {
        for (String body : new String[] {"{", "[]", "{}", "{\"reservationId\":\"" + RESERVATION + "\"}", "{\"reservationId\":\"x\",\"expiresAt\":\"2026-10-18T12:15:00.123Z\"}",
                "{\"reservationId\":\"" + RESERVATION + "\",\"expiresAt\":\"tomorrow\"}"}) {
            peer.then(201, body);

            assertThrows(DependencyUnavailableException.class, () -> client.reserve(ORDER, PRODUCT, 2), body);
        }
    }
}
