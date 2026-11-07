package dgm.delivery.delivery.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerCallResult;
import dgm.kit.client.EndpointGuard;
import dgm.kit.client.SellerCaller;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент системы продавца (TC-963, ADR-028): подписанный запрос с {@code requestId}, {@code orderNumber}, {@code productId}, {@code quantity} и
 * {@code respondBy} и без данных покупателя; ответ 2xx это «принято», 5xx, тайм-аут и обрыв это повторяемый сбой, 4xx и перенаправление это отказ,
 * запрещённый адрес до соединения не доходит.
 */
@Tag("TC-963")
class SellerApiClientTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final PendingRequest REQUEST = new PendingRequest(UUID.fromString("0199e0a0-0000-7000-8000-000000000a01"),
            UUID.fromString("0199e0a0-0000-7000-8000-000000000b01"), UUID.fromString("0199e0a0-0000-7000-8000-000000000301"), SELLER, PRODUCT, 1042, 2, NOW,
            NOW.plus(Duration.ofMinutes(10)), 0);
    private static final SecretValue SECRET = SecretValue.of("0123456789abcdef0123456789abcdef");

    private record Sent(EndpointGuard.Target target, Map<String, String> headers, String body) {
    }

    /** Транспорт без сети: запоминает отправленное и отвечает по сценарию. */
    private static final class Transport implements SellerCaller.Transport {
        final List<Sent> sent = new ArrayList<>();
        SellerCaller.Response response = new SellerCaller.Response(200, new byte[0]);
        SellerCaller.CallException failure;

        @Override
        public SellerCaller.Response exchange(EndpointGuard.Target target, Map<String, String> headers, byte[] body, Duration timeout)
                throws SellerCaller.CallException {
            sent.add(new Sent(target, headers, new String(body, StandardCharsets.UTF_8)));
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    private Transport transport;
    private SellerApiClient client;

    @BeforeEach
    void setUp() {
        transport = new Transport();
        EndpointGuard guard = new EndpointGuard(host -> new InetAddress[] {address(host)}, Set.of());
        client = new SellerApiClient(new SellerCaller(guard, transport, new ManualClock(NOW)));
    }

    private static InetAddress address(String host) throws UnknownHostException {
        return InetAddress.getByName(host.startsWith("internal") ? "10.0.0.5" : "93.184.216.34");
    }

    private SellerCallResult send(String url) {
        return client.send(url, SECRET, REQUEST);
    }

    @Test
    void theRequestIsSignedAndCarriesOnlyTheFieldsOfTheContract() {
        assertInstanceOf(SellerCallResult.Accepted.class, send("https://shop.seller.example/dgm"));

        assertEquals(1, transport.sent.size());
        Sent sent = transport.sent.get(0);
        assertEquals("shop.seller.example", sent.target().host());
        assertEquals("/dgm", sent.target().target());
        Map<String, Object> body = Json.parseObject(sent.body());
        assertEquals(Set.of("requestId", "orderNumber", "productId", "quantity", "respondBy"), body.keySet(), "поля запроса по контракту, ничего лишнего");
        assertEquals(REQUEST.requestId().toString(), body.get("requestId"));
        assertEquals(1042, ((Number) body.get("orderNumber")).intValue());
        assertEquals(PRODUCT.toString(), body.get("productId"));
        assertEquals(2, ((Number) body.get("quantity")).intValue());
        assertEquals("2026-11-07T10:10:00.000Z", body.get("respondBy"));
        assertEquals(REQUEST.requestId().toString(), sent.headers().get("X-Request-Id"));
        assertTrue(sent.headers().containsKey(WebhookSignature.SIGNATURE_HEADER), "запрос подписан");
        assertTrue(sent.headers().containsKey(WebhookSignature.TIMESTAMP_HEADER));
    }

    @Test
    void thereIsNoDataOfTheBuyerInTheRequest() {
        send("https://shop.seller.example/dgm");

        String body = transport.sent.get(0).body();
        assertFalse(body.contains(REQUEST.orderId().toString()), "внутренний идентификатор заказа продавцу не отдаётся, только короткий номер");
        assertFalse(body.contains("@"), "адреса покупателя в запросе нет");
        assertFalse(body.contains("buyer") || body.contains("email") || body.contains("address"));
    }

    @Test
    void anyCodeOfTheFamily2xxIsAccepted() {
        for (int status : new int[] {200, 201, 202, 204}) {
            transport.response = new SellerCaller.Response(status, new byte[0]);
            assertInstanceOf(SellerCallResult.Accepted.class, send("https://shop.seller.example/dgm"), "код " + status);
        }
    }

    @Test
    void aServerErrorIsAFailureThatIsRepeatedInsideTheWindow() {
        for (int status : new int[] {500, 502, 503, 504}) {
            transport.response = new SellerCaller.Response(status, "oops".getBytes(StandardCharsets.UTF_8));
            SellerCallResult result = send("https://shop.seller.example/dgm");
            assertEquals(new SellerCallResult.Retryable("seller_http_" + status), result);
        }
    }

    @Test
    void aTimeoutAndABrokenConnectionAreRepeatedToo() {
        transport.failure = new SellerCaller.CallException(SellerCaller.CallException.Kind.TIMEOUT, "тайм-аут", null);
        assertEquals(new SellerCallResult.Retryable("seller_timeout"), send("https://shop.seller.example/dgm"));
        transport.failure = new SellerCaller.CallException(SellerCaller.CallException.Kind.IO, "обрыв", null);
        assertEquals(new SellerCallResult.Retryable("seller_io"), send("https://shop.seller.example/dgm"));
        transport.failure = new SellerCaller.CallException(SellerCaller.CallException.Kind.TOO_LARGE, "много", null);
        assertEquals(new SellerCallResult.Retryable("seller_too_large"), send("https://shop.seller.example/dgm"));
        transport.failure = new SellerCaller.CallException(SellerCaller.CallException.Kind.PROTOCOL, "не HTTP", null);
        assertEquals(new SellerCallResult.Retryable("seller_protocol"), send("https://shop.seller.example/dgm"));
    }

    @Test
    void aClientErrorAndARedirectAreARefusalOfTheSeller() {
        for (int status : new int[] {301, 302, 400, 401, 403, 404, 409, 422}) {
            transport.response = new SellerCaller.Response(status, new byte[0]);
            assertEquals(new SellerCallResult.Rejected(status), send("https://shop.seller.example/dgm"), "код " + status);
        }
    }

    @Test
    void anAddressThatFailsTheGuardIsNeverCalled() {
        assertInstanceOf(SellerCallResult.EndpointRejected.class, send("https://internal.seller.example/dgm"));
        assertInstanceOf(SellerCallResult.EndpointRejected.class, send("http://shop.seller.example/dgm"));
        assertInstanceOf(SellerCallResult.EndpointRejected.class, send("https://shop.seller.example:9000/dgm"));

        assertTrue(transport.sent.isEmpty(), "до соединения дело не дошло");
    }
}
