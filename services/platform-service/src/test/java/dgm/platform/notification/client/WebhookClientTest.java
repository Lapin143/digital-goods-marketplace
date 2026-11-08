package dgm.platform.notification.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.EndpointGuard;
import dgm.kit.client.SellerCaller;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.WebhookCallResult;
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
 * Клиент webhook продавцу (TC-970, Р-193): тело уходит теми байтами, что лежат в базе, запрос подписан секретом продавца и несёт метку времени и
 * идентификатор события; 2xx это успех, любой другой исход неудача попытки с коротким кодом; запрещённый адрес до соединения не доходит.
 */
@Tag("TC-970")
class WebhookClientTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID EVENT = UUID.fromString("0199e0a0-0000-7000-8000-000000000b01");
    private static final String BODY = "{\"eventId\":\"0199e0a0-0000-7000-8000-000000000b01\",\"type\":\"order.issued\",\"orderNumber\":1042}";
    private static final PendingWebhook WEBHOOK = new PendingWebhook(UUID.fromString("0199e0a0-0000-7000-8000-000000000a01"), SELLER, EVENT, "order.issued",
            BODY, 0);
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
    private WebhookClient client;

    @BeforeEach
    void setUp() {
        transport = new Transport();
        EndpointGuard guard = new EndpointGuard(host -> new InetAddress[] {address(host)}, Set.of());
        client = new WebhookClient(new SellerCaller(guard, transport, new ManualClock(NOW)));
    }

    private static InetAddress address(String host) throws UnknownHostException {
        return InetAddress.getByName(host.startsWith("internal") ? "10.0.0.5" : "93.184.216.34");
    }

    private WebhookCallResult send(String url) {
        return client.send(url, SECRET, WEBHOOK);
    }

    @Test
    void theBodyGoesOutAsItIsStoredWithTheEventIdAndTheTypeInTheHeaders() {
        assertTrue(send("https://shop.seller.example/dgm/webhook").accepted());

        assertEquals(1, transport.sent.size());
        Sent sent = transport.sent.get(0);
        assertEquals("shop.seller.example", sent.target().host());
        assertEquals("/dgm/webhook", sent.target().target());
        assertEquals(BODY, sent.body(), "те же байты, что в базе: повтор шлёт то же тело");
        assertEquals(EVENT.toString(), sent.headers().get("X-Event-Id"), "по нему продавец отсекает дубли");
        assertEquals("order.issued", sent.headers().get("X-Event-Type"));
    }

    @Test
    void theRequestIsSignedAndTheSignatureAndTheTimestampPassTheVerificationOfTheSeller() {
        send("https://shop.seller.example/dgm/webhook");

        Map<String, String> headers = transport.sent.get(0).headers();
        assertTrue(headers.containsKey(WebhookSignature.SIGNATURE_HEADER));
        assertEquals("2026-11-09T10:00:00.000Z", headers.get(WebhookSignature.TIMESTAMP_HEADER));
        assertEquals(WebhookSignature.Result.VALID, WebhookSignature.verify(List.of(SECRET), headers.get(WebhookSignature.TIMESTAMP_HEADER),
                headers.get(WebhookSignature.SIGNATURE_HEADER), BODY.getBytes(StandardCharsets.UTF_8), NOW));
        assertEquals(WebhookSignature.Result.SIGNATURE_INVALID, WebhookSignature.verify(List.of(SecretValue.of("another-secret-0123456789abcdef")),
                headers.get(WebhookSignature.TIMESTAMP_HEADER), headers.get(WebhookSignature.SIGNATURE_HEADER),
                BODY.getBytes(StandardCharsets.UTF_8), NOW), "подпись чужим секретом не проходит");
        assertEquals(WebhookSignature.Result.EXPIRED, WebhookSignature.verify(List.of(SECRET), headers.get(WebhookSignature.TIMESTAMP_HEADER),
                headers.get(WebhookSignature.SIGNATURE_HEADER), BODY.getBytes(StandardCharsets.UTF_8), NOW.plus(Duration.ofMinutes(6))),
                "метка времени ограничивает повтор перехваченного запроса");
    }

    @Test
    void anyCodeOfTheFamily2xxIsASuccessAndTheCodeIsKept() {
        for (int status : new int[] {200, 201, 202, 204}) {
            transport.response = new SellerCaller.Response(status, new byte[0]);
            WebhookCallResult result = send("https://shop.seller.example/dgm/webhook");
            assertTrue(result.accepted(), "код " + status);
            assertEquals(status, result.responseCode());
            assertNull(result.errorCode());
        }
    }

    @Test
    void anyOtherCodeIsAFailureOfTheAttemptWithAShortCode() {
        for (int status : new int[] {301, 302, 400, 401, 404, 409, 429, 500, 502, 503}) {
            transport.response = new SellerCaller.Response(status, "no".getBytes(StandardCharsets.UTF_8));
            WebhookCallResult result = send("https://shop.seller.example/dgm/webhook");
            assertFalse(result.accepted(), "код " + status);
            assertEquals(status, result.responseCode());
            assertEquals("seller_http_" + status, result.errorCode());
        }
    }

    @Test
    void aTimeoutABrokenConnectionAndAnUnreadableAnswerLeaveNoResponseCode() {
        for (SellerCaller.CallException.Kind kind : SellerCaller.CallException.Kind.values()) {
            transport.failure = new SellerCaller.CallException(kind, "сбой", null);
            WebhookCallResult result = send("https://shop.seller.example/dgm/webhook");
            assertFalse(result.accepted());
            assertNull(result.responseCode());
            assertEquals("seller_" + kind.name().toLowerCase(java.util.Locale.ROOT), result.errorCode());
        }
    }

    @Test
    void aCodeOutsideTheRangeOfHttpIsTreatedAsAnUnreadableAnswer() {
        for (int status : new int[] {0, 99, 600, 999}) {
            transport.response = new SellerCaller.Response(status, new byte[0]);
            WebhookCallResult result = send("https://shop.seller.example/dgm/webhook");
            assertFalse(result.accepted(), "код " + status);
            assertNull(result.responseCode(), "база такой код не примет");
            assertEquals("seller_protocol", result.errorCode());
        }
    }

    @Test
    void anAddressThatFailsTheGuardIsNeverCalledAndIsAFailureOfTheAttempt() {
        for (String url : List.of("https://internal.seller.example/dgm", "http://shop.seller.example/dgm", "https://shop.seller.example:9000/dgm")) {
            WebhookCallResult result = send(url);
            assertFalse(result.accepted(), url);
            assertNull(result.responseCode());
            assertEquals("endpoint_rejected", result.errorCode());
        }
        assertTrue(transport.sent.isEmpty(), "до соединения дело не дошло");
    }
}
