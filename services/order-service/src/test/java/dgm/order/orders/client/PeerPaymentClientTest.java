package dgm.order.orders.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.idempotency.IdempotencyKeys;
import dgm.kit.json.Json;
import dgm.order.orders.client.PaymentClient.Opened;
import dgm.order.orders.client.PaymentClient.Refused;
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
 * Клиент сервиса платежей на локальном HTTP-сервере: тело открытия сессии и ключ идемпотентности, отказ по смыслу (4xx) как исход, 5xx и тишина как
 * недоступность без повторов (повторы шлюзу делает сам сервис платежей, ADR-015).
 */
@Tag("TC-513")
class PeerPaymentClientTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final Instant END = Instant.parse("2026-10-18T12:12:00.123Z");
    private static final String URL = "https://localhost:8443/payment/pay/pay_1";
    private static final String OK = "{\"paymentId\":\"0199e0a0-0000-7000-8000-000000000401\",\"orderId\":\"" + ORDER + "\",\"status\":\"created\","
            + "\"sessionUrl\":\"" + URL + "\",\"sessionExpiresAt\":\"2026-10-18T12:12:00.123Z\"}";

    private StubPeer peer;
    private PeerPaymentClient client;

    @BeforeEach
    void start() throws IOException {
        peer = StubPeer.start(201, OK);
        client = new PeerPaymentClient(() -> peer.client(PeerPaymentClient.PEER, "", Duration.ofMillis(500)));
    }

    @AfterEach
    void stop() {
        peer.stop();
    }

    @Test
    void theSessionIsRequestedWithTheOrderTheAmountAndTheEndAndADerivedKey() {
        assertEquals(new Opened(URL, END), client.open(ORDER, 299_800, "RUB", END));

        assertEquals(1, peer.seen().size());
        StubPeer.Seen seen = peer.seen().get(0);
        assertEquals("POST", seen.method());
        assertEquals("/internal/v1/payment-sessions", seen.path());
        assertEquals(Map.of("orderId", ORDER.toString(), "amount", Map.of("amount", 299_800, "currency", "RUB"), "sessionExpiresAt", "2026-10-18T12:12:00.123Z"),
                Json.parseObject(seen.body()));
        assertEquals(IdempotencyKeys.derived("open-session", ORDER).toString(), seen.headers().get("idempotency-key"));
    }

    @Test
    void aRefusalByMeaningIsAnOutcome() {
        for (int status : new int[] {409, 422}) {
            peer.then(status, "{\"type\":\"https://api.marketplace.example/problems/state-conflict\"}");

            assertEquals(new Refused(status), client.open(ORDER, 299_800, "RUB", END));
        }
    }

    @Test
    void aServerErrorIsUnavailableAndIsNotRetried() {
        peer.then(503, "{\"type\":\"https://api.marketplace.example/problems/payment-session-unavailable\"}");

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.open(ORDER, 299_800, "RUB", END));

        assertEquals(PeerPaymentClient.PEER, e.peer());
        assertEquals(503, e.lastStatus());
        assertEquals(1, peer.seen().size(), "повторов нет");
    }

    @Test
    void aSilentPaymentServiceIsUnavailableByTimeoutAfterOneAttempt() {
        peer.thenAfter(1500, 201, OK);

        DependencyUnavailableException e = assertThrows(DependencyUnavailableException.class, () -> client.open(ORDER, 299_800, "RUB", END));

        assertEquals(DependencyUnavailableException.TIMEOUT, e.reason());
        assertEquals(1, peer.seen().size());
    }

    @Test
    void anAnswerNotInTheFormatIsNotTrusted() {
        for (String body : new String[] {"{", "[]", "{}", "{\"sessionUrl\":\"" + URL + "\"}", "{\"sessionUrl\":\"" + URL + "\",\"sessionExpiresAt\":\"soon\"}",
                "{\"sessionUrl\":5,\"sessionExpiresAt\":\"2026-10-18T12:12:00.123Z\"}"}) {
            peer.then(201, body);

            assertThrows(DependencyUnavailableException.class, () -> client.open(ORDER, 299_800, "RUB", END), body);
        }
    }
}
