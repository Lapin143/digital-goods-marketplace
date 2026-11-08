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
 * Сервис остатков в процессе теста: внутренние операции {@code GET /internal/v1/keys?orderId=} (OP-38) и {@code POST /internal/v1/seller-keys}
 * (OP-99) на локальном HTTP-сервере JDK. Отдаёт значения ключей, которые тест выдал заказу (в том числе принятые от продавца), и по режиму отвечает
 * 503 (сосед недоступен) или 409 (ключи ещё не выданы). Неизвестный заказ это 404. Ключи продавца принимаются по правилам настоящего сервиса:
 * число равно заказанному ({@link #expectSellerKeys}), дубликат в вызове это 422, те же ключи повторно это 200, другие это 409. Настоящий сервис остатков
 * проверяют его тесты и сквозные проверки К6 и К15; здесь важно управлять отказами и паузами и считать вызовы.
 */
final class InventoryStub {

    /** Режим соседа. */
    enum Mode {
        OK, DOWN, NOT_ISSUED
    }

    /** Принятый вызов. */
    record Call(UUID orderId, Map<String, String> headers) {
    }

    /** Принятый вызов {@code storeSellerKeys}. */
    record SellerKeysCall(UUID orderId, UUID requestId, List<String> keys, Map<String, String> headers) {
    }

    private static final String PROBLEM = "{\"type\":\"https://api.marketplace.example/problems/%s\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"}";

    private final HttpServer server;
    private final Map<UUID, List<String>> keys = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<UUID, Integer> sellerQuantity = new ConcurrentHashMap<>();
    private final List<SellerKeysCall> sellerCalls = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.OK;
    private volatile Runnable beforeStore = () -> {
    };

    private InventoryStub(HttpServer server) {
        this.server = server;
    }

    static InventoryStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            InventoryStub stub = new InventoryStub(server);
            server.createContext("/internal/v1/keys", stub::onKeys);
            server.createContext("/internal/v1/seller-keys", stub::onSellerKeys);
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
        sellerCalls.clear();
        mode = Mode.OK;
        beforeStore = () -> {
        };
    }

    /** Заказ по API: сколько ключей продавец обязан прислать (резерв {@code used} с этим количеством). Без этого вызов получает 404. */
    void expectSellerKeys(UUID orderId, int quantity) {
        sellerQuantity.put(orderId, quantity);
    }

    /** Действие перед разбором ключей продавца: искусственная пауза приёма (медленный сосед) или проверка состояния посреди приёма. */
    void beforeStore(Runnable action) {
        beforeStore = action;
    }

    /** Вызовы {@code storeSellerKeys} по заказу. */
    List<SellerKeysCall> sellerCalls(UUID orderId) {
        return sellerCalls.stream().filter(c -> orderId.equals(c.orderId())).toList();
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

    /** {@code storeSellerKeys}: тело {@code orderId}, {@code requestId}, {@code keys}; значения принимаются как есть (шифрование проверяют тесты сервиса остатков). */
    @SuppressWarnings("unchecked")
    private void onSellerKeys(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        Map<String, Object> body;
        try {
            body = Json.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            reply(exchange, 400, problem("bad-request", "Некорректный запрос", 400));
            return;
        }
        UUID orderId = UUID.fromString((String) body.get("orderId"));
        UUID requestId = UUID.fromString((String) body.get("requestId"));
        List<String> keys = List.copyOf((List<String>) body.get("keys"));
        sellerCalls.add(new SellerKeysCall(orderId, requestId, keys, headers));
        beforeStore.run();
        if (mode == Mode.DOWN) {
            reply(exchange, 503, "");
            return;
        }
        Integer quantity = sellerQuantity.get(orderId);
        if (quantity == null) {
            reply(exchange, 404, problem("not-found", "Не найдено", 404));
            return;
        }
        synchronized (this) {
            List<String> existing = storedKeys(orderId);
            if (existing != null) {
                if (existing.equals(keys)) {
                    exchange.getResponseHeaders().add("Cache-Control", "no-store");
                    reply(exchange, 200, Json.write(Map.of("orderId", orderId.toString(), "stored", keys.size())));
                } else {
                    reply(exchange, 409, problem("state-conflict", "Ключи заказа уже выданы", 409));
                }
                return;
            }
            if (keys.size() != quantity) {
                reply(exchange, 422, validation("/keys", "out_of_range"));
                return;
            }
            for (int i = 0; i < keys.size(); i++) {
                if (keys.indexOf(keys.get(i)) != i) {
                    reply(exchange, 422, validation("/keys/" + i, "not_unique"));
                    return;
                }
            }
            this.keys.put(orderId, keys);
        }
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        reply(exchange, 200, Json.write(Map.of("orderId", orderId.toString(), "stored", keys.size())));
    }

    private List<String> storedKeys(UUID orderId) {
        return keys.get(orderId);
    }

    private static String validation(String pointer, String code) {
        return "{\"type\":\"https://api.marketplace.example/problems/validation-failed\",\"title\":\"Данные не прошли проверку\",\"status\":422,"
                + "\"code\":\"validation-failed\",\"errors\":[{\"pointer\":\"" + pointer + "\",\"code\":\"" + code + "\"}]}";
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
