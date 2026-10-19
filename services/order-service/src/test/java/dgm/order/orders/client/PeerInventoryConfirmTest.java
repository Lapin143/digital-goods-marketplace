package dgm.order.orders.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.idempotency.IdempotencyKeys;
import dgm.kit.json.Json;
import dgm.order.orders.client.InventoryClient.ConfirmResult;
import dgm.order.orders.client.InventoryClient.Confirmed;
import dgm.order.orders.client.InventoryClient.NoKeys;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Подтверждение резерва после оплаты (ADR-013, {@code confirmReservation}) на локальном HTTP-сервере: тело и ключ идемпотентности (ключ шага
 * «confirm» отличается от ключа резерва того же заказа), исходы «подтверждён», «заново зарезервирован», «ключей нет» и «резерва нет совсем»,
 * один повтор после 5xx с тем же ключом, остальные отказы и ответы не по формату это недоступность.
 */
@Tag("TC-517")
class PeerInventoryConfirmTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final UUID RESERVATION = UUID.fromString("0199e0a0-0000-7000-8000-000000000501");
    private static final String USED = "{\"reservationId\":\"" + RESERVATION + "\",\"orderId\":\"" + ORDER + "\",\"productId\":\"" + PRODUCT
            + "\",\"quantity\":2,\"status\":\"used\",\"expiresAt\":\"2026-10-18T12:15:00.123Z\",\"reReserved\":%s}";
    private static final String PROBLEM = "{\"type\":\"https://api.marketplace.example/problems/%s\",\"code\":\"%s\",\"available\":%d}";

    private StubPeer peer;
    private PeerInventoryClient client;

    @BeforeEach
    void start() throws IOException {
        peer = StubPeer.start(200, String.format(USED, false));
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
    void theConfirmationIsRequestedWithTheOrderTheProductAndTheQuantityAndItsOwnKey() {
        ConfirmResult result = client.confirm(ORDER, PRODUCT, 2);

        assertEquals(new Confirmed(RESERVATION, false), result);
        assertEquals(1, peer.seen().size());
        StubPeer.Seen seen = peer.seen().get(0);
        assertEquals("POST", seen.method());
        assertEquals("/internal/v1/reservations/confirm", seen.path());
        assertEquals(Map.of("orderId", ORDER.toString(), "productId", PRODUCT.toString(), "quantity", 2), Json.parseObject(seen.body()));
        assertEquals(IdempotencyKeys.derived("confirm", ORDER).toString(), seen.headers().get("idempotency-key"));
        assertNotEquals(IdempotencyKeys.derived("reserve", ORDER).toString(), seen.headers().get("idempotency-key"), "шаги резерва и подтверждения не делят ключ");
    }

    @Test
    void aReservationCreatedAgainIsReported() {
        peer.then(200, String.format(USED, true));

        assertEquals(new Confirmed(RESERVATION, true), client.confirm(ORDER, PRODUCT, 2));
    }

    @Test
    void aMissingReReservedFlagMeansTheOldReservationWasConfirmed() {
        peer.then(200, "{\"reservationId\":\"" + RESERVATION + "\"}");

        assertEquals(new Confirmed(RESERVATION, false), client.confirm(ORDER, PRODUCT, 2));
    }

    @Test
    void notEnoughFreeKeysIsAnOutcomeWithTheirNumberAndIsNotRetried() {
        peer.then(409, problem("insufficient-stock", 1));

        assertEquals(new NoKeys(1), client.confirm(ORDER, PRODUCT, 2));
        assertEquals(1, peer.seen().size());
    }

    @Test
    void aMissingOrNegativeNumberOfFreeKeysIsTreatedAsZero() {
        peer.then(409, "{\"type\":\"https://api.marketplace.example/problems/insufficient-stock\",\"code\":\"insufficient-stock\"}");
        peer.then(409, problem("insufficient-stock", -3));

        assertEquals(new NoKeys(0), client.confirm(ORDER, PRODUCT, 2));
        assertEquals(new NoKeys(0), client.confirm(ORDER, PRODUCT, 2));
    }

    @Test
    void noReservationAtAllIsTreatedAsNoKeys() {
        peer.then(404, problem("not-found", 0));

        assertEquals(new NoKeys(0), client.confirm(ORDER, PRODUCT, 2));
        assertEquals(1, peer.seen().size());
    }

    @Test
    void aRetryAfterAServerErrorCarriesTheSameKeyAndItsAnswerIsUsed() {
        peer.then(503, "");

        assertEquals(new Confirmed(RESERVATION, false), client.confirm(ORDER, PRODUCT, 2));

        assertEquals(2, peer.seen().size());
        assertEquals(peer.seen().get(0).headers().get("idempotency-key"), peer.seen().get(1).headers().get("idempotency-key"));
    }

    @Test
    void twoServerErrorsInARowMeanTheInventoryServiceIsUnavailable() {
        peer.then(503, "");
        peer.then(502, "");

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.confirm(ORDER, PRODUCT, 2));

        assertEquals(PeerInventoryClient.PEER, e.peer());
        assertEquals(2, peer.seen().size());
    }

    @Test
    void aSilentInventoryServiceMeansUnavailableByTimeout() {
        peer.thenAfter(1500, 200, String.format(USED, false));
        peer.thenAfter(1500, 200, String.format(USED, false));

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.confirm(ORDER, PRODUCT, 2));

        assertEquals(DependencyUnavailableException.TIMEOUT, e.reason());
    }

    @Test
    void anyOtherRefusalIsUnavailableWithoutARetry() {
        for (int status : new int[] {400, 403, 422}) {
            peer.then(status, "");

            DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.confirm(ORDER, PRODUCT, 2), "статус " + status);

            assertEquals(status, e.lastStatus());
        }
        peer.then(409, problem("state-conflict", 0));
        assertThrows(DependencyUnavailableException.class, () -> client.confirm(ORDER, PRODUCT, 2));
        assertEquals(4, peer.seen().size());
    }

    @Test
    void anAnswerNotInTheFormatIsNotTrusted() {
        for (String body : new String[] {"{", "[]", "{}", "{\"reservationId\":\"x\"}", "{\"reservationId\":7}"}) {
            peer.then(200, body);

            assertThrows(DependencyUnavailableException.class, () -> client.confirm(ORDER, PRODUCT, 2), body);
        }
    }
}
