package dgm.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code order-service} на локальном HTTP-сервере JDK для интеграционного теста платформы: принимает
 * {@code POST /internal/v1/orders/{orderId}/update-delivery-address} и действует по ключу идемпотентности, как настоящий (первый вызов с ключом это
 * действие, повтор с тем же ключом ответ без действия). Запоминает каждый запрос. Настоящий сервис заказов и обмен по mTLS проверяют
 * {@code OrderAddressIT} (сторона заказов) и стенд (К10); здесь важно, что именно и с каким ключом просит платформа.
 */
final class OrderStub implements AutoCloseable {

    /** Принятый запрос. */
    record Seen(String path, String body, String key) {
    }

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Set<String> effects = ConcurrentHashMap.newKeySet();
    private volatile int failWith;

    OrderStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Все следующие запросы получают этот код (503: сосед недоступен); {@code 0} возвращает обычную работу. */
    void failWith(int status) {
        failWith = status;
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    List<Seen> seenFor(String orderId) {
        return seen.stream().filter(s -> s.path().contains("/" + orderId + "/")).toList();
    }

    /** Сколько разных ключей привело к действию (адрес в заказе обновлён, событие опубликовано). */
    int effectsFor(String orderId) {
        return (int) seen.stream().filter(s -> s.path().contains("/" + orderId + "/") && effects.contains(s.key())).map(Seen::key).distinct().count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = exchange.getRequestHeaders().entrySet().stream().filter(e -> e.getKey().toLowerCase(Locale.ROOT).equals("idempotency-key"))
                .map(e -> e.getValue().get(0)).findFirst().orElse("");
        seen.add(new Seen(exchange.getRequestURI().getRawPath(), body, key));
        int status = failWith;
        String answer;
        if (status != 0) {
            answer = "{\"code\":\"dependency-unavailable\"}";
        } else {
            status = 200;
            boolean replay = !effects.add(key);
            if (replay) {
                exchange.getResponseHeaders().add("Idempotency-Replayed", "true");
            }
            answer = "{\"updatedAt\":\"2026-10-26T12:00:00.000Z\"}";
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
