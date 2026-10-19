package dgm.order.orders.client;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.client.PeerClient;
import dgm.kit.retry.RetryPolicy;
import dgm.kit.retry.Sleeper;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Сосед на локальном HTTP-сервере JDK для тестов клиентов саги: отвечает по сценарию (очередь ответов, пустая очередь это ответ по умолчанию),
 * запоминает запросы. Настоящий {@link PeerClient} без TLS и без настоящих пауз между попытками.
 */
final class StubPeer {

    /** Принятый запрос. */
    record Seen(String method, String path, String body, Map<String, String> headers) {
    }

    /** Ответ сценария; {@code delayMillis} держит ответ, чтобы вызвать тайм-аут попытки. */
    record Reply(int status, String body, long delayMillis) {
    }

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Reply> script = new ConcurrentLinkedDeque<>();
    private final String fallback;
    private final ManualClock clock = new ManualClock(Instant.parse("2026-10-18T10:00:00Z"));

    private StubPeer(HttpServer server, String fallback) {
        this.server = server;
        this.fallback = fallback;
    }

    /** Запускает сервер; {@code fallback} это тело ответа 200 (или 201), когда сценарий пуст. */
    static StubPeer start(int fallbackStatus, String fallbackBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        StubPeer peer = new StubPeer(server, fallbackStatus + ":" + fallbackBody);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            peer.seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body, headers));
            Reply reply = peer.script.pollFirst();
            if (reply == null) {
                int colon = peer.fallback.indexOf(':');
                reply = new Reply(Integer.parseInt(peer.fallback.substring(0, colon)), peer.fallback.substring(colon + 1), 0);
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException e) {
                // Клиент уже ушёл по тайм-ауту
            } finally {
                exchange.close();
            }
        });
        server.start();
        return peer;
    }

    void stop() {
        server.stop(0);
    }

    /** Следующие ответы по порядку. */
    void then(int status, String body) {
        script.addLast(new Reply(status, body, 0));
    }

    void thenAfter(long delayMillis, int status, String body) {
        script.addLast(new Reply(status, body, delayMillis));
    }

    List<Seen> seen() {
        return seen;
    }

    URI base() {
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    /** Клиент соседа: попытка 1 секунда, повторов столько, сколько паузы в {@code retry} (например «200ms» это один повтор), паузы не ждёт. */
    PeerClient client(String peer, String retry, Duration attemptTimeout) {
        PeerClient.Settings settings = PeerClient.Settings.of(peer, base(), new Random(5))
                .withRetry(RetryPolicy.parse(retry, 0.0, new Random(5)))
                .withTimeouts(attemptTimeout, Duration.ofSeconds(20))
                .withBreaker(1000, 0.5, Duration.ofSeconds(30));
        Sleeper sleeper = clock::advance;
        return new PeerClient(settings, null, clock, sleeper, new SimpleMeterRegistry());
    }
}
