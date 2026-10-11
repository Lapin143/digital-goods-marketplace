package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Вызовы платформы против настоящего HTTP-сервера на loopback: путь, заголовки, тело и разбор ответов. */
class HttpPlatformOtpTest {

    private static final String PHONE = "+79001234567";
    private static final String USER = "120fcee1-8c5f-4b89-8a1c-4c02a9375eb9";

    private record Seen(String method, String path, String contentType, String idempotencyKey, String body) {
    }

    private HttpServer server;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile String retryAfter;
    private int keyCounter;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) {
            exchange.getResponseHeaders().add("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private HttpPlatformOtp client() {
        URI base = URI.create("http://localhost:" + server.getAddress().getPort());
        return new HttpPlatformOtp(HttpClient.newHttpClient(), base, () -> "key-" + (++keyCounter), Duration.ofSeconds(3));
    }

    @Test
    void issueSendsPurposePhoneAndIpWithFreshIdempotencyKey() {
        status = 202;
        assertInstanceOf(PlatformOtp.Issued.Accepted.class, client().issue(PHONE, "203.0.113.5"));
        Seen call = seen.get(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/v1/otp-codes", call.path());
        assertEquals("application/json", call.contentType());
        assertEquals("key-1", call.idempotencyKey());
        assertEquals("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"clientIp\":\"203.0.113.5\"}", call.body());
    }

    @Test
    void everyAttemptGetsItsOwnKey() {
        status = 202;
        HttpPlatformOtp otp = client();
        otp.issue(PHONE, null);
        otp.issue(PHONE, null);
        assertNotEquals(seen.get(0).idempotencyKey(), seen.get(1).idempotencyKey());
        assertEquals("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\"}", seen.get(0).body());
    }

    @Test
    void randomKeysAreUuids() {
        String key = HttpPlatformOtp.randomKeys().get();
        assertTrue(key.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"), key);
        assertNotEquals(key, HttpPlatformOtp.randomKeys().get());
    }

    @Test
    void clientIpOfUnexpectedShapeIsNotSent() {
        status = 202;
        client().issue(PHONE, "1.2.3.4\",\"phone\":\"+70000000000");
        assertEquals("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\"}", seen.get(0).body());
    }

    @Test
    void issueLimitedReadsRetryAfter() {
        status = 429;
        retryAfter = "125";
        assertEquals(new PlatformOtp.Issued.Limited(125), client().issue(PHONE, null));
    }

    @Test
    void retryAfterMissingBrokenOrHugeIsBounded() {
        status = 429;
        retryAfter = null;
        assertEquals(new PlatformOtp.Issued.Limited(60), client().issue(PHONE, null));
        retryAfter = "soon";
        assertEquals(new PlatformOtp.Issued.Limited(60), client().issue(PHONE, null));
        retryAfter = "999999999";
        assertEquals(new PlatformOtp.Issued.Limited(86400), client().issue(PHONE, null));
        retryAfter = "0";
        assertEquals(new PlatformOtp.Issued.Limited(1), client().issue(PHONE, null));
    }

    @Test
    void serverErrorsAndUnexpectedCodesAreUnavailable() {
        for (int code : new int[] {500, 503, 400, 403, 200}) {
            status = code;
            assertInstanceOf(PlatformOtp.Issued.Unavailable.class, client().issue(PHONE, null), "код " + code);
        }
    }

    @Test
    void connectionRefusedIsUnavailable() {
        server.stop(0);
        assertInstanceOf(PlatformOtp.Issued.Unavailable.class, client().issue(PHONE, null));
        assertInstanceOf(PlatformOtp.Verified.Unavailable.class, client().verify(PHONE, "123456"));
    }

    @Test
    void verifyAcceptedReturnsUserId() {
        status = 200;
        responseBody = "{\"userId\":\"" + USER.toUpperCase() + "\",\"extra\":1}";
        PlatformOtp.Verified result = client().verify(PHONE, "123456");
        assertEquals(new PlatformOtp.Verified.Accepted(USER), result);
        Seen call = seen.get(0);
        assertEquals("/internal/v1/otp-verifications", call.path());
        assertEquals("{\"purpose\":\"sms_login\",\"phone\":\"+79001234567\",\"code\":\"123456\"}", call.body());
    }

    @Test
    void verifyWithoutUserIdOrWithBrokenIdIsUnavailable() {
        status = 200;
        responseBody = "{}";
        assertInstanceOf(PlatformOtp.Verified.Unavailable.class, client().verify(PHONE, "123456"));
        responseBody = "{\"userId\":\"not-a-uuid\"}";
        assertInstanceOf(PlatformOtp.Verified.Unavailable.class, client().verify(PHONE, "123456"));
    }

    @Test
    void verifyInvalidAndExpiredAreTold() {
        status = 422;
        responseBody = "{\"status\":422,\"code\":\"otp-invalid\",\"attemptsLeft\":3}";
        assertInstanceOf(PlatformOtp.Verified.Invalid.class, client().verify(PHONE, "123456"));
        responseBody = "{\"status\":422,\"code\":\"otp-expired\"}";
        assertInstanceOf(PlatformOtp.Verified.Expired.class, client().verify(PHONE, "123456"));
        responseBody = "not json";
        assertInstanceOf(PlatformOtp.Verified.Invalid.class, client().verify(PHONE, "123456"));
    }

    @Test
    void verifyLockedReadsRetryAfter() {
        status = 429;
        retryAfter = "900";
        responseBody = "{\"status\":429,\"code\":\"otp-locked\"}";
        assertEquals(new PlatformOtp.Verified.Locked(900), client().verify(PHONE, "123456"));
    }

    @Test
    void verifyServerErrorIsUnavailable() {
        status = 503;
        assertInstanceOf(PlatformOtp.Verified.Unavailable.class, client().verify(PHONE, "123456"));
    }

    @Test
    void argumentsAreCheckedBeforeAnyCall() {
        HttpPlatformOtp otp = client();
        assertThrows(IllegalArgumentException.class, () -> otp.issue("89001234567", null));
        assertThrows(IllegalArgumentException.class, () -> otp.issue(null, null));
        assertThrows(IllegalArgumentException.class, () -> otp.verify(PHONE, "12345"));
        assertThrows(IllegalArgumentException.class, () -> otp.verify(PHONE, null));
        assertThrows(IllegalArgumentException.class, () -> otp.verify("+7900\",\"x", "123456"));
        assertTrue(seen.isEmpty());
    }
}
