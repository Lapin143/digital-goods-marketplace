package dgm.kit.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.secret.SecretValue;
import dgm.kit.storage.ObjectStorage.PresignedLink;
import dgm.kit.storage.client.S3ObjectStorage;
import dgm.kit.storage.client.S3Settings;
import dgm.kit.storage.client.SigV4;
import dgm.kit.testing.TestCertificates;
import dgm.kit.time.ManualClock;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Клиент S3 на локальном сервере HTTPS: что именно уходит в хранилище (метод, путь, тело, подпись) и как клиент отвечает на отказы.
 * Подпись проверяется пересчётом на стороне «сервера» по тому, что он получил, в том числе по заголовку Host: если клиент подпишет
 * один адрес, а отправит на другой, хранилище ответило бы 403, и тест это ловит.
 */
class S3ObjectStorageTest {

    private static final String ACCESS_KEY = "catalog-service";
    private static final String SECRET = "storage-secret-value";
    private static final String KEY = "0199e0a0-0000-7000-8000-000000000101/0199e0a0-0000-7000-8000-000000000901";

    private record Seen(String method, String path, String query, byte[] body, Map<String, String> headers) {
        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static final TestCertificates.Material CERTIFICATE = TestCertificates.selfSigned("localhost");

    private HttpsServer server;
    private SSLContext trust;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile Function<Seen, Integer> status = request -> 200;
    private ManualClock clock;

    @BeforeEach
    void start() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("k", CERTIFICATE.key(), new char[0], new Certificate[] {CERTIFICATE.certificate()});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);

        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        anchors.setCertificateEntry("ca", CERTIFICATE.certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(anchors);
        trust = SSLContext.getInstance("TLS");
        trust.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0)));
            Seen request = new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestBody().readAllBytes(), headers);
            seen.add(request);
            int code = status.apply(request);
            byte[] answer = code / 100 == 2 ? new byte[0] : "<Error><Code>AccessDenied</Code><Resource>secret-detail</Resource></Error>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, answer.length == 0 ? -1 : answer.length);
            if (answer.length > 0) {
                exchange.getResponseBody().write(answer);
            }
            exchange.close();
        });
        server.start();
        clock = new ManualClock(Instant.parse("2026-10-11T10:00:00Z"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int port() {
        return server.getAddress().getPort();
    }

    private S3ObjectStorage storage(URI publicBase) {
        S3Settings settings = new S3Settings(URI.create("https://localhost:" + port()), "us-east-1", "seller-documents", ACCESS_KEY,
                SecretValue.of(SECRET), publicBase, Duration.ofSeconds(3));
        return new S3ObjectStorage(settings, trust, clock);
    }

    private S3ObjectStorage storage() {
        return storage(URI.create("https://localhost:" + port()));
    }

    private static SigV4 signer() {
        return new SigV4(ACCESS_KEY, SecretValue.of(SECRET), "us-east-1");
    }

    // ------------------------------------------------------------------------------------------------ запись

    @Test
    void putSendsSignedRequestWithBodyAndContentType() {
        byte[] content = "%PDF-1.7 тело".getBytes(StandardCharsets.UTF_8);
        storage().put(KEY, content, "application/pdf");

        assertEquals(1, seen.size());
        Seen request = seen.get(0);
        assertEquals("PUT", request.method());
        assertEquals("/seller-documents/" + KEY, request.path());
        assertArrayEquals(content, request.body());
        assertEquals("application/pdf", request.header("content-type"));
        assertEquals(SigV4.sha256Hex(content), request.header("x-amz-content-sha256"));
        assertEquals("20261011T100000Z", request.header("x-amz-date"));

        // «Сервер» повторяет подпись по полученному: Host, дата, хеш тела и тип содержимого должны дать ту же строку
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("content-type", request.header("content-type"));
        signed.put("host", request.header("host"));
        signed.put("x-amz-content-sha256", request.header("x-amz-content-sha256"));
        signed.put("x-amz-date", request.header("x-amz-date"));
        String expected = signer().authorization("PUT", request.path(), signed, request.header("x-amz-content-sha256"), clock.instant());
        assertEquals(expected, request.header("authorization"));
        assertEquals("localhost:" + port(), request.header("host"));
    }

    @Test
    void deleteIsSignedAndMissingObjectIsNotAnError() {
        storage().delete(KEY);
        status = request -> 404;
        storage().delete(KEY);
        assertEquals(2, seen.size());
        Seen first = seen.get(0);
        assertEquals("DELETE", first.method());
        assertEquals("/seller-documents/" + KEY, first.path());
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", first.header("host"));
        signed.put("x-amz-content-sha256", first.header("x-amz-content-sha256"));
        signed.put("x-amz-date", first.header("x-amz-date"));
        assertEquals(signer().authorization("DELETE", first.path(), signed, first.header("x-amz-content-sha256"), clock.instant()),
                first.header("authorization"));
    }

    @Test
    void refusalIsReportedAsUnavailableWithoutLeakingStorageDetails() {
        status = request -> 403;
        ProblemException put = assertThrows(ProblemException.class, () -> storage().put(KEY, new byte[] {1}, "image/png"));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, put.type());
        assertEquals("5", put.headers().get("Retry-After"));
        assertFalse(put.getMessage().contains("secret-detail"));
        assertFalse(put.getMessage().contains("AccessDenied"));

        status = request -> 500;
        ProblemException delete = assertThrows(ProblemException.class, () -> storage().delete(KEY));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, delete.type());
    }

    @Test
    void stoppedStorageIsReportedAsUnavailable() {
        S3ObjectStorage storage = storage();
        server.stop(0);
        ProblemException e = assertThrows(ProblemException.class, () -> storage.put(KEY, new byte[] {1}, "image/png"));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
    }

    @Test
    void untrustedCertificateIsRefused() throws Exception {
        TestCertificates.Material stranger = TestCertificates.selfSigned("localhost");
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        anchors.setCertificateEntry("other", stranger.certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(anchors);
        SSLContext other = SSLContext.getInstance("TLS");
        other.init(null, tmf.getTrustManagers(), null);
        S3Settings settings = new S3Settings(URI.create("https://localhost:" + port()), "us-east-1", "seller-documents", ACCESS_KEY,
                SecretValue.of(SECRET), URI.create("https://localhost:" + port()), Duration.ofSeconds(3));
        S3ObjectStorage storage = new S3ObjectStorage(settings, other, clock);
        assertThrows(ProblemException.class, () -> storage.put(KEY, new byte[] {1}, "image/png"));
        assertTrue(seen.isEmpty(), "до хранилища с чужим сертификатом запрос не дошёл");
    }

    @Test
    void insecureEndpointIsRejectedAtConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new S3Settings(URI.create("http://object-storage:9000"), "us-east-1", "b", "k",
                SecretValue.of("s"), URI.create("https://localhost:8443/files"), Duration.ofSeconds(1)));
    }

    // ------------------------------------------------------------------------------------------------ ссылка

    @Test
    void presignedLinkLivesFiveMinutesAndIsSignedForThePublicAddress() {
        URI gateway = URI.create("https://localhost:8443/files");
        PresignedLink link = storage(gateway).presignGet(KEY, Duration.ofMinutes(5));

        assertEquals(clock.instant().plusSeconds(300), link.expiresAt());
        assertEquals("https", link.url().getScheme());
        assertEquals("localhost", link.url().getHost());
        assertEquals(8443, link.url().getPort());
        assertEquals("/files/seller-documents/" + KEY, link.url().getRawPath());
        String query = link.url().getRawQuery();
        assertTrue(query.contains("X-Amz-Expires=300"), query);
        assertTrue(query.contains("X-Amz-Date=20261011T100000Z"), query);
        assertTrue(query.contains("X-Amz-SignedHeaders=host"), query);

        // Хранилище видит путь без префикса шлюза и тот же Host: подпись обязана сойтись
        String expected = signer().presignedQuery("GET", "localhost:8443", "/seller-documents/" + KEY, clock.instant(), 300);
        assertEquals(expected, query);
        assertTrue(seen.isEmpty(), "подпись считается без вызова хранилища");
    }

    @Test
    void linkToTheStorageItselfHasNoPrefix() {
        PresignedLink link = storage().presignGet(KEY, Duration.ofSeconds(300));
        assertEquals("/seller-documents/" + KEY, link.url().getRawPath());
        assertEquals("localhost", link.url().getHost());
        assertEquals(port(), link.url().getPort());
        assertNotNull(link.url().getRawQuery());
    }

    @Test
    void linkExpiryFollowsTheClock() {
        S3ObjectStorage storage = storage();
        PresignedLink first = storage.presignGet(KEY, Duration.ofMinutes(5));
        clock.advance(Duration.ofSeconds(301));
        PresignedLink second = storage.presignGet(KEY, Duration.ofMinutes(5));
        assertEquals(Instant.parse("2026-10-11T10:05:00Z"), first.expiresAt());
        assertEquals(Instant.parse("2026-10-11T10:10:01Z"), second.expiresAt());
        assertFalse(first.url().getRawQuery().equals(second.url().getRawQuery()));
    }

    @Test
    void invalidKeysAreRejected() {
        S3ObjectStorage storage = storage();
        assertThrows(IllegalArgumentException.class, () -> storage.presignGet("", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> storage.presignGet("/leading", Duration.ofMinutes(5)));
        assertThrows(IllegalArgumentException.class, () -> storage.put(KEY, new byte[] {1}, "bad\ntype"));
    }
}
