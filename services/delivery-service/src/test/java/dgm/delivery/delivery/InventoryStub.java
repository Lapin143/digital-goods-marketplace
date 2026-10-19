package dgm.delivery.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dgm.kit.json.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Сервис остатков в процессе теста: одна внутренняя операция {@code GET /internal/v1/keys?orderId=} (OP-38) на локальном HTTP-сервере JDK.
 * Отдаёт значения ключей, которые тест выдал заказу, и по режиму отвечает 503 (сосед недоступен) или 409 (ключи ещё не выданы). Неизвестный
 * заказ это 404. Настоящий сервис остатков проверяют его тесты и сквозные проверки К6; здесь важно управлять отказами и считать вызовы.
 */
final class InventoryStub {

    /** Режим соседа. */
    enum Mode {
        OK, DOWN, NOT_ISSUED
    }

    /** Принятый вызов. */
    record Call(UUID orderId, Map<String, String> headers) {
    }

    private static final String PROBLEM = "{\"type\":\"https://api.marketplace.example/problems/%s\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"}";

    private final HttpServer server;
    private final Map<UUID, List<String>> keys = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.OK;

    private InventoryStub(HttpServer server) {
        this.server = server;
    }

    static InventoryStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            InventoryStub stub = new InventoryStub(server);
            server.createContext("/internal/v1/keys", stub::onKeys);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("Сервис остатков теста не запущен", e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------------------------------------ сценарий теста

    /** Очищает записанные вызовы и возвращает рабочий режим. Выданные ключи остаются: у каждого теста свои заказы. */
    void reset() {
        calls.clear();
        mode = Mode.OK;
    }

    void modeIs(Mode value) {
        mode = value;
    }

    /** Выдаёт заказу {@code count} ключей со случайными значениями и возвращает их (порядок выдачи сохраняется). */
    List<String> issue(UUID orderId, int count) {
        String prefix = "KEY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            values.add(prefix + "-" + i);
        }
        keys.put(orderId, List.copyOf(values));
        return values;
    }

    /** Вызовы по заказу. */
    List<Call> calls(UUID orderId) {
        return calls.stream().filter(c -> orderId.equals(c.orderId())).toList();
    }

    // ------------------------------------------------------------------------------------------------ сервер

    private void onKeys(HttpExchange exchange) throws IOException {
        UUID orderId = orderId(exchange.getRequestURI().getRawQuery());
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        calls.add(new Call(orderId, headers));
        if (mode == Mode.DOWN) {
            reply(exchange, 503, "");
            return;
        }
        if (orderId == null || !"GET".equals(exchange.getRequestMethod())) {
            reply(exchange, 400, problem("bad-request", "Некорректный запрос", 400));
            return;
        }
        List<String> values = keys.get(orderId);
        if (values == null) {
            reply(exchange, 404, problem("not-found", "Не найдено", 404));
            return;
        }
        if (mode == Mode.NOT_ISSUED) {
            reply(exchange, 409, problem("state-conflict", "Ключи заказа ещё не выданы", 409));
            return;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyId", new UUID(0x0199e0a000007000L, 0x8000000000000000L + i + 1).toString());
            item.put("value", values.get(i));
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("keys", items);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        reply(exchange, 200, Json.write(body));
    }

    private static UUID orderId(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith("orderId=")) {
                try {
                    return UUID.fromString(pair.substring("orderId=".length()));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String problem(String code, String title, int status) {
        return String.format(PROBLEM, code, title, status, code);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
