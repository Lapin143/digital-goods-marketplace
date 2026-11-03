package dgm.kit.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.SellerCaller.CallException;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.secret.SecretValue;
import dgm.kit.testing.TestCertificates;
import dgm.kit.time.Timestamps;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;

/**
 * {@link SellerCaller}: проверка адреса до вызова, подпись и заголовки, отсутствие перенаправлений, предел ответа и срок. Первая часть идёт
 * на подставном транспорте, вторая на настоящем TLS-сервере с самоподписанным сертификатом (сокет подключается к проверенному адресу,
 * имя проверяется по сертификату).
 */
class SellerCallerTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);
    private static final SecretValue SECRET = SecretValue.of("a3c1e5b7-test-seller-secret-for-unit-tests-only");
    private static final byte[] BODY = "{\"requestId\":\"r1\",\"quantity\":2}".getBytes(StandardCharsets.UTF_8);
    private static final TestCertificates.Material MATERIAL = TestCertificates.selfSigned("seller-test");

    private static EndpointGuard guardFor(Map<String, String> names, Set<String> allow) {
        return new EndpointGuard(host -> {
            String literal = names.get(host);
            if (literal == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(literal)};
        }, allow);
    }

    // ---- подставной транспорт ----

    private record Sent(EndpointGuard.Target target, Map<String, String> headers, byte[] body, Duration timeout) {
    }

    private static final class RecordingTransport implements SellerCaller.Transport {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        SellerCaller.Response response = new SellerCaller.Response(200, "{}".getBytes(StandardCharsets.UTF_8));
        CallException failure;

        @Override
        public SellerCaller.Response exchange(EndpointGuard.Target target, Map<String, String> headers, byte[] body, Duration timeout)
                throws CallException {
            sent.add(new Sent(target, headers, body, timeout));
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    @Test
    void postSignsBodyWithTimestampFromInjectedClock() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        SellerCaller caller = new SellerCaller(guardFor(Map.of("seller.example", "93.184.216.34"), Set.of()), transport, FIXED);

        SellerCaller.Response response = caller.post("https://seller.example/hook", BODY, SECRET, Map.of("X-Event-Id", "evt-1"));

        assertEquals(200, response.status());
        assertTrue(response.success());
        assertEquals(1, transport.sent.size());
        Sent sent = transport.sent.get(0);
        assertArrayEquals(BODY, sent.body());
        assertEquals(SellerCaller.TIMEOUT, sent.timeout());
        assertEquals(Duration.ofSeconds(5), sent.timeout());
        assertEquals("93.184.216.34", sent.target().address().getHostAddress());
        assertEquals("/hook", sent.target().target());
        Map<String, String> headers = sent.headers();
        assertEquals("evt-1", headers.get("X-Event-Id"));
        assertEquals(SellerCaller.CONTENT_TYPE, headers.get("Content-Type"));
        assertEquals(Timestamps.format(FIXED.instant()), headers.get(WebhookSignature.TIMESTAMP_HEADER));
        assertEquals(WebhookSignature.Result.VALID, WebhookSignature.verify(List.of(SECRET), headers.get(WebhookSignature.TIMESTAMP_HEADER),
                headers.get(WebhookSignature.SIGNATURE_HEADER), BODY, FIXED.instant()));
        assertEquals(WebhookSignature.sign("a3c1e5b7-test-seller-secret-for-unit-tests-only".getBytes(StandardCharsets.UTF_8),
                headers.get(WebhookSignature.TIMESTAMP_HEADER), BODY), headers.get(WebhookSignature.SIGNATURE_HEADER));
    }

    @Test
    void forbiddenAddressNeverReachesTheTransport() {
        Map<String, String> bad = Map.ofEntries(
                Map.entry("a.example", "127.0.0.1"), Map.entry("b.example", "10.0.0.1"), Map.entry("c.example", "172.16.0.1"),
                Map.entry("d.example", "192.168.0.1"), Map.entry("e.example", "100.64.0.1"), Map.entry("f.example", "169.254.169.254"),
                Map.entry("g.example", "0.0.0.0"), Map.entry("h.example", "::1"), Map.entry("i.example", "fd00::1"),
                Map.entry("j.example", "fe80::1"), Map.entry("k.example", "::ffff:10.0.0.1"), Map.entry("l.example", "224.0.0.1"));
        RecordingTransport transport = new RecordingTransport();
        SellerCaller caller = new SellerCaller(guardFor(bad, Set.of()), transport, FIXED);
        for (String host : bad.keySet()) {
            assertThrows(EndpointGuard.RejectedException.class, () -> caller.post("https://" + host + "/hook", BODY, SECRET, Map.of()), host);
        }
        assertThrows(EndpointGuard.RejectedException.class, () -> caller.post("http://a.example/hook", BODY, SECRET, Map.of()));
        assertThrows(EndpointGuard.RejectedException.class, () -> caller.post("https://a.example:9000/hook", BODY, SECRET, Map.of()));
        assertEquals(0, transport.sent.size());
    }

    @Test
    void addressIsCheckedOnEveryCallNotOnlyOnce() throws Exception {
        // Первый вызов: имя ведёт на публичный адрес. Потом продавец перенастроил DNS на внутренний адрес: второй вызов отвергается
        List<String> answers = new ArrayList<>(List.of("93.184.216.34", "10.0.0.5"));
        EndpointGuard guard = new EndpointGuard(host -> new InetAddress[] {InetAddress.getByName(answers.remove(0))}, Set.of());
        RecordingTransport transport = new RecordingTransport();
        SellerCaller caller = new SellerCaller(guard, transport, FIXED);

        caller.post("https://rebind.example/hook", BODY, SECRET, Map.of());
        EndpointGuard.RejectedException e = assertThrows(EndpointGuard.RejectedException.class,
                () -> caller.post("https://rebind.example/hook", BODY, SECRET, Map.of()));

        assertEquals(EndpointGuard.Reason.FORBIDDEN_ADDRESS, e.reason());
        assertEquals(1, transport.sent.size());
    }

    @Test
    void validateUsesTheSameRules() {
        SellerCaller caller = new SellerCaller(guardFor(Map.of("ok.example", "93.184.216.34", "bad.example", "10.0.0.1"), Set.of()), new RecordingTransport(), FIXED);
        caller.validate("https://ok.example/hook");
        assertEquals(EndpointGuard.Reason.FORBIDDEN_ADDRESS,
                assertThrows(EndpointGuard.RejectedException.class, () -> caller.validate("https://bad.example/hook")).reason());
    }

    @Test
    void serviceHeadersCannotBeOverriddenOrForged() {
        RecordingTransport transport = new RecordingTransport();
        SellerCaller caller = new SellerCaller(guardFor(Map.of("seller.example", "93.184.216.34"), Set.of()), transport, FIXED);
        for (String name : List.of("X-Webhook-Signature", "x-webhook-timestamp", "Content-Type", "Content-Length", "Host", "Connection", "Transfer-Encoding")) {
            assertThrows(IllegalArgumentException.class, () -> caller.post("https://seller.example/", BODY, SECRET, Map.of(name, "x")), name);
        }
        assertThrows(IllegalArgumentException.class, () -> caller.post("https://seller.example/", BODY, SECRET, Map.of("X-Event-Id", "a\r\nX-Evil: 1")));
        assertThrows(IllegalArgumentException.class, () -> caller.post("https://seller.example/", BODY, SECRET, Map.of("X-Event\r\nId", "a")));
        assertThrows(IllegalArgumentException.class, () -> caller.post("https://seller.example/", BODY, SECRET, Map.of("X Event", "a")));
        assertEquals(0, transport.sent.size());
    }

    @Test
    void failuresCarryNoAddressBodyOrSecret() {
        RecordingTransport transport = new RecordingTransport();
        transport.failure = new CallException(CallException.Kind.TIMEOUT, "Продавец не ответил за отведённое время", null);
        SellerCaller caller = new SellerCaller(guardFor(Map.of("seller.example", "93.184.216.34"), Set.of()), transport, FIXED);

        CallException e = assertThrows(CallException.class, () -> caller.post("https://seller.example/private?token=abc", BODY, SECRET, Map.of()));

        assertEquals(CallException.Kind.TIMEOUT, e.kind());
        assertFalse(e.getMessage().contains("seller.example"));
        assertFalse(e.getMessage().contains("token"));
        assertFalse(e.getMessage().contains(SECRET.reveal()));
        assertEquals("SecretValue[****]", SECRET.toString());
    }

    @Test
    void responseKeepsItsBodyAndDoesNotExposeInternalArray() {
        byte[] original = "ok".getBytes(StandardCharsets.UTF_8);
        SellerCaller.Response response = new SellerCaller.Response(503, original);
        original[0] = 'X';
        assertEquals("ok", response.text());
        response.body()[0] = 'Y';
        assertEquals("ok", response.text());
        assertFalse(response.success());
        assertTrue(new SellerCaller.Response(204, new byte[0]).success());
        assertFalse(new SellerCaller.Response(302, new byte[0]).success());
    }

    // ---- настоящий TLS ----

    /** Сервер на одном соединении: читает запрос целиком, записывает его и отвечает тем, что задал тест. */
    private static final class TlsServer implements AutoCloseable {
        interface Responder {
            void respond(OutputStream out) throws Exception;
        }

        final SSLServerSocket socket;
        final AtomicInteger connections = new AtomicInteger();
        volatile String head = "";
        volatile byte[] body = new byte[0];
        private final Thread thread;
        private volatile boolean closed;

        TlsServer(Responder responder) throws Exception {
            socket = (SSLServerSocket) serverContext().getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!closed) {
                    try (SSLSocket client = (SSLSocket) socket.accept()) {
                        connections.incrementAndGet();
                        client.setSoTimeout(10_000);
                        read(client.getInputStream());
                        responder.respond(client.getOutputStream());
                    } catch (Exception e) {
                        // обрыв, неудачное рукопожатие и закрытие сервера в конце теста: проверяет клиент
                    }
                }
            }, "seller-test-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void read(InputStream in) throws IOException {
            ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int c = in.read();
                if (c < 0) {
                    break;
                }
                headBytes.write(c);
                matched = (c == '\r' && (matched == 0 || matched == 2)) || (c == '\n' && (matched == 1 || matched == 3)) ? matched + 1 : (c == '\r' ? 1 : 0);
            }
            head = headBytes.toString(StandardCharsets.UTF_8);
            int length = 0;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            body = in.readNBytes(length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            socket.close();
        }
    }

    private static SSLContext serverContext() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("k", MATERIAL.key(), "changeit".toCharArray(), new Certificate[] {MATERIAL.certificate()});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientTrusting(boolean trustTheServer) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        if (trustTheServer) {
            store.setCertificateEntry("ca", MATERIAL.certificate());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    private static SellerCaller.Transport socketTransport(boolean trustTheServer) throws Exception {
        return new SellerCaller.SocketTransport(clientTrusting(trustTheServer).getSocketFactory(), Clock.systemUTC());
    }

    private static EndpointGuard.Target target(String host, int port, String path) throws UnknownHostException {
        return new EndpointGuard.Target(host, port, InetAddress.getByName("127.0.0.1"), path);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final Map<String, String> NO_HEADERS = Map.of();

    @Test
    void realTlsRequestIsSignedAndAnswerIsRead() throws Exception {
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"status\":\"ok\"}")))) {
            SellerCaller.Transport real = socketTransport(true);
            // Порт 8443 разрешён защитой, а сервер теста слушает другой: транспорт получает цель с настоящим портом
            SellerCaller.Transport toTestPort = (t, h, b, timeout) -> real.exchange(new EndpointGuard.Target(t.host(), server.port(), t.address(), t.target()), h, b, timeout);
            SellerCaller caller = new SellerCaller(guardFor(Map.of("localhost", "127.0.0.1"), Set.of("localhost")), toTestPort, Clock.systemUTC());

            SellerCaller.Response response = caller.post("https://localhost:8443/seller/hook?x=1", BODY, SECRET, Map.of("X-Event-Id", "evt-7"));

            assertEquals(202, response.status());
            assertEquals("{\"status\":\"ok\"}", response.text());
            assertTrue(server.head.startsWith("POST /seller/hook?x=1 HTTP/1.1\r\n"), server.head);
            assertTrue(server.head.contains("\r\nHost: localhost:" + server.port() + "\r\n"), server.head);
            assertTrue(server.head.contains("\r\nConnection: close\r\n"), server.head);
            assertTrue(server.head.contains("\r\nContent-Length: " + BODY.length + "\r\n"), server.head);
            assertTrue(server.head.contains("\r\nX-Event-Id: evt-7\r\n"), server.head);
            assertArrayEquals(BODY, server.body);
            String timestamp = headerOf(server.head, "X-Webhook-Timestamp");
            String signature = headerOf(server.head, "X-Webhook-Signature");
            assertNotNull(timestamp);
            assertEquals(WebhookSignature.Result.VALID, WebhookSignature.verify(List.of(SECRET), timestamp, signature, BODY, Instant.now()));
        }
    }

    private static String headerOf(String head, String name) {
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith(name.toLowerCase(java.util.Locale.ROOT) + ":")) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    @Test
    void chunkedAnswerIsDecoded() throws Exception {
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5;ext=1\r\npedia\r\n0\r\nX-Trailer: 1\r\n\r\n")))) {
            SellerCaller.Response response = socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5));
            assertEquals("Wikipedia", response.text());
        }
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1:1/internal\r\nContent-Length: 0\r\n\r\n")))) {
            SellerCaller.Response response = socketTransport(true).exchange(target("localhost", server.port(), "/hook"), NO_HEADERS, BODY, Duration.ofSeconds(5));
            assertEquals(302, response.status());
            assertFalse(response.success());
            assertEquals(1, server.connections.get());
        }
    }

    @Test
    void answerAtTheLimitIsAcceptedAndOneByteMoreIsNot() throws Exception {
        byte[] atLimit = new byte[SellerCaller.MAX_RESPONSE_BYTES];
        java.util.Arrays.fill(atLimit, (byte) 'a');
        try (TlsServer server = new TlsServer(out -> {
            out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: " + atLimit.length + "\r\n\r\n"));
            out.write(atLimit);
        })) {
            SellerCaller.Response response = socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5));
            assertEquals(SellerCaller.MAX_RESPONSE_BYTES, response.body().length);
        }
        try (TlsServer server = new TlsServer(out -> {
            out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: " + (atLimit.length + 1) + "\r\n\r\n"));
            out.write(atLimit);
            out.write('a');
        })) {
            assertEquals(CallException.Kind.TOO_LARGE, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
    }

    @Test
    void oversizedAnswerIsCutWhateverTheFraming() throws Exception {
        byte[] chunk = new byte[40_000];
        java.util.Arrays.fill(chunk, (byte) 'b');
        // Блоками: два блока по 40 000 байт дают 80 000 > 65 536
        try (TlsServer server = new TlsServer(out -> {
            out.write(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"));
            for (int i = 0; i < 2; i++) {
                out.write(ascii(Integer.toHexString(chunk.length) + "\r\n"));
                out.write(chunk);
                out.write(ascii("\r\n"));
            }
            out.write(ascii("0\r\n\r\n"));
        })) {
            assertEquals(CallException.Kind.TOO_LARGE, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
        // Без длины, до закрытия соединения
        try (TlsServer server = new TlsServer(out -> {
            out.write(ascii("HTTP/1.1 200 OK\r\n\r\n"));
            out.write(chunk);
            out.write(chunk);
        })) {
            assertEquals(CallException.Kind.TOO_LARGE, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
        // Бесконечные заголовки
        try (TlsServer server = new TlsServer(out -> {
            out.write(ascii("HTTP/1.1 200 OK\r\n"));
            for (int i = 0; i < 100; i++) {
                out.write(ascii("X-Pad-" + i + ": " + "p".repeat(400) + "\r\n"));
            }
            out.write(ascii("\r\n"));
        })) {
            assertEquals(CallException.Kind.TOO_LARGE, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
    }

    @Test
    void silentSellerEndsInTimeoutNotInHangingCall() throws Exception {
        try (TlsServer server = new TlsServer(out -> Thread.sleep(3000))) {
            long started = System.nanoTime();
            CallException e = assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofMillis(400)));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertEquals(CallException.Kind.TIMEOUT, e.kind());
            assertTrue(elapsedMs < 2500, "ждали " + elapsedMs + " мс");
        }
    }

    @Test
    void slowDripCannotStretchTheCallPastItsDeadline() throws Exception {
        // Каждый байт приходит раньше тайм-аута чтения, но весь ответ дольше срока вызова
        try (TlsServer server = new TlsServer(out -> {
            byte[] answer = ascii("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc");
            for (byte b : answer) {
                out.write(b);
                out.flush();
                Thread.sleep(120);
            }
        })) {
            long started = System.nanoTime();
            CallException e = assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofMillis(700)));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertEquals(CallException.Kind.TIMEOUT, e.kind());
            assertTrue(elapsedMs < 2000, "ждали " + elapsedMs + " мс");
        }
    }

    @Test
    void untrustedCertificateAndWrongNameAreRefused() throws Exception {
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")))) {
            // Сертификат не из хранилища доверия клиента
            assertEquals(CallException.Kind.IO, assertThrows(CallException.class,
                    () -> socketTransport(false).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
            // Сертификат доверенный, но выдан не на это имя: соединение идёт к тому же адресу, имя другое
            assertEquals(CallException.Kind.IO, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("other.example", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
            assertTrue(server.head.isEmpty() || !server.head.contains("X-Webhook"), "запрос не должен уйти по недоверенному соединению");
        }
    }

    @Test
    void garbageAndBrokenAnswersAreProtocolAndIoErrors() throws Exception {
        try (TlsServer server = new TlsServer(out -> out.write(ascii("<html>not http</html>\r\n\r\n")))) {
            assertEquals(CallException.Kind.PROTOCOL, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc")))) {
            assertEquals(CallException.Kind.IO, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 200 OK\r\nContent-Length: -5\r\n\r\n")))) {
            assertEquals(CallException.Kind.PROTOCOL, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n")))) {
            assertEquals(CallException.Kind.PROTOCOL, assertThrows(CallException.class,
                    () -> socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5))).kind());
        }
    }

    @Test
    void serverErrorStatusIsAnAnswerNotAnException() throws Exception {
        try (TlsServer server = new TlsServer(out -> out.write(ascii("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 4\r\n\r\nbusy")))) {
            SellerCaller.Response response = socketTransport(true).exchange(target("localhost", server.port(), "/"), NO_HEADERS, BODY, Duration.ofSeconds(5));
            assertEquals(503, response.status());
            assertFalse(response.success());
            assertEquals("busy", response.text());
        }
    }

    @Test
    void refusedConnectionIsAnIoError() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            closedPort = probe.getLocalPort();
        }
        int port = closedPort;
        assertEquals(CallException.Kind.IO, assertThrows(CallException.class,
                () -> socketTransport(true).exchange(target("localhost", port, "/"), NO_HEADERS, BODY, Duration.ofSeconds(2))).kind());
    }
}
