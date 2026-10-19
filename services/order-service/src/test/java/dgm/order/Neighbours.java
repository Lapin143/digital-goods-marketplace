package dgm.order;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dgm.kit.json.Json;
import dgm.kit.time.Timestamps;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Три соседа саги в одном процессе теста: каталог, сервис остатков и сервис платежей на одном локальном HTTP-сервере JDK (по путям внутренних
 * операций). Ведут себя так, как описано в OpenAPI: остатки идемпотентны по ключу и не уходят в минус, платёжная сессия одна на заказ, каталог
 * отвечает 404 о неизвестном товаре. Любого соседа можно «уронить» (ответ 503). Подтверждение резерва после оплаты отвечает по режиму
 * {@link ConfirmMode}. Все вызовы записываются.
 *
 * <p>Порядок вызовов и их число проверяет тест, поэтому запись не очищается между вызовами, только явным {@link #reset()}.
 */
final class Neighbours {

    /** Режим соседа. */
    enum Mode {
        OK, DOWN
    }

    /** Ответ сервиса остатков на подтверждение резерва после оплаты (ADR-013). */
    enum ConfirmMode {
        /** Активный резерв подтверждён. */
        OK,
        /** Резерв был снят, ключи взяты заново ({@code reReserved = true}). */
        RE_RESERVED,
        /** Свободных ключей меньше заказанного: 409 {@code insufficient-stock}. */
        NO_KEYS,
        /** Резерва этого заказа нет совсем: 404. */
        NOT_FOUND,
        /** Сервис остатков не отвечает: 503. */
        DOWN
    }

    /** Принятый вызов. */
    record Call(String neighbour, String method, String path, String body, Map<String, String> headers) {
    }

    private static final String PROBLEM = "{\"type\":\"https://api.marketplace.example/problems/%s\",\"title\":\"%s\",\"status\":%d,\"code\":\"%s\"%s}";

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> products = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> stock = new ConcurrentHashMap<>();
    private final Map<String, String> reservations = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> confirmations = new ConcurrentHashMap<>();
    private volatile Mode catalog = Mode.OK;
    private volatile Mode inventory = Mode.OK;
    private volatile Mode payment = Mode.OK;
    private volatile ConfirmMode confirm = ConfirmMode.OK;
    private volatile int confirmAvailable;

    private Neighbours(HttpServer server) {
        this.server = server;
    }

    static Neighbours start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            Neighbours neighbours = new Neighbours(server);
            server.createContext("/internal/v1/products/", neighbours::onCatalog);
            server.createContext("/internal/v1/reservations", neighbours::onInventory);
            server.createContext("/internal/v1/payment-sessions", neighbours::onPayment);
            server.start();
            return neighbours;
        } catch (IOException e) {
            throw new IllegalStateException("Соседи теста не запущены", e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------------------------------------ сценарий теста

    /** Очищает записанные вызовы и возвращает всех соседей в рабочий режим. Товары и остатки остаются: каждый тест берёт свои. */
    void reset() {
        calls.clear();
        catalog = Mode.OK;
        inventory = Mode.OK;
        payment = Mode.OK;
        confirm = ConfirmMode.OK;
        confirmAvailable = 0;
    }

    void catalogIs(Mode mode) {
        catalog = mode;
    }

    void inventoryIs(Mode mode) {
        inventory = mode;
    }

    void paymentIs(Mode mode) {
        payment = mode;
    }

    /** Как сервис остатков ответит на подтверждение резерва; {@code available} это число свободных ключей в ответе {@link ConfirmMode#NO_KEYS}. */
    void confirmIs(ConfirmMode mode, int available) {
        confirm = mode;
        confirmAvailable = available;
    }

    void confirmIs(ConfirmMode mode) {
        confirmIs(mode, 0);
    }

    /** Новый опубликованный товар с остатком ключей. */
    UUID product(UUID seller, String title, long price, int keys) {
        return product(seller, title, price, keys, "published");
    }

    UUID product(UUID seller, String title, long price, int keys, String status) {
        UUID id = UUID.randomUUID();
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", price);
        money.put("currency", "RUB");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("productId", id.toString());
        card.put("sellerId", seller.toString());
        card.put("title", title);
        card.put("status", status);
        card.put("price", money);
        card.put("issuanceMethod", "key_pool");
        card.put("productType", "game_key");
        products.put(id, Json.write(card));
        stock.put(id, keys);
        return id;
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    List<Call> calls(String neighbour) {
        return calls.stream().filter(c -> c.neighbour().equals(neighbour)).toList();
    }

    /** Сколько ключей осталось у товара. */
    int keysLeft(UUID product) {
        synchronized (stock) {
            return stock.getOrDefault(product, 0);
        }
    }

    // ------------------------------------------------------------------------------------------------ соседи

    private void onCatalog(HttpExchange exchange) throws IOException {
        Call call = capture("catalog", exchange);
        if (catalog == Mode.DOWN) {
            reply(exchange, 503, "");
            return;
        }
        String id = call.path().substring(call.path().lastIndexOf('/') + 1);
        String card;
        try {
            card = products.get(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            card = null;
        }
        if (card == null) {
            reply(exchange, 404, problem("not-found", "Не найдено", 404, ""));
        } else {
            reply(exchange, 200, card);
        }
    }

    private void onInventory(HttpExchange exchange) throws IOException {
        Call call = capture("inventory", exchange);
        if (inventory == Mode.DOWN) {
            reply(exchange, 503, "");
            return;
        }
        if (call.path().endsWith("/reservations/confirm")) {
            onConfirm(exchange, call);
            return;
        }
        Map<String, Object> body = Json.parseObject(call.body());
        String key = call.headers().get("idempotency-key");
        UUID product = UUID.fromString((String) body.get("productId"));
        int quantity = ((Number) body.get("quantity")).intValue();
        synchronized (stock) {
            String saved = key == null ? null : reservations.get(key);
            if (saved != null) {
                reply(exchange, 201, saved);
                return;
            }
            Integer available = stock.get(product);
            if (available == null) {
                reply(exchange, 404, problem("not-found", "Не найдено", 404, ""));
                return;
            }
            if (available < quantity) {
                reply(exchange, 409, problem("insufficient-stock", "Недостаточно ключей", 409, ",\"available\":" + available));
                return;
            }
            stock.put(product, available - quantity);
            Map<String, Object> reservation = new LinkedHashMap<>();
            reservation.put("reservationId", UUID.randomUUID().toString());
            reservation.put("orderId", body.get("orderId"));
            reservation.put("productId", body.get("productId"));
            reservation.put("quantity", quantity);
            reservation.put("status", "active");
            reservation.put("expiresAt", Timestamps.format(Instant.now().plus(Duration.ofMinutes(15))));
            String json = Json.write(reservation);
            if (key != null) {
                reservations.put(key, json);
            }
            reply(exchange, 201, json);
        }
    }

    /** {@code POST /internal/v1/reservations/confirm}: идемпотентно по ключу, повтор после успеха отдаёт тот же резерв. */
    private void onConfirm(HttpExchange exchange, Call call) throws IOException {
        Map<String, Object> body = Json.parseObject(call.body());
        String key = call.headers().get("idempotency-key");
        ConfirmMode mode = confirm;
        if (mode == ConfirmMode.DOWN) {
            reply(exchange, 503, "");
            return;
        }
        String saved = key == null ? null : confirmations.get(key);
        if (saved != null) {
            reply(exchange, 200, saved);
            return;
        }
        if (mode == ConfirmMode.NOT_FOUND) {
            reply(exchange, 404, problem("not-found", "Не найдено", 404, ""));
            return;
        }
        if (mode == ConfirmMode.NO_KEYS) {
            reply(exchange, 409, problem("insufficient-stock", "Недостаточно ключей", 409, ",\"available\":" + confirmAvailable));
            return;
        }
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("reservationId", UUID.randomUUID().toString());
        reservation.put("orderId", body.get("orderId"));
        reservation.put("productId", body.get("productId"));
        reservation.put("quantity", body.get("quantity"));
        reservation.put("status", "used");
        reservation.put("expiresAt", Timestamps.format(Instant.now().plus(Duration.ofMinutes(15))));
        reservation.put("reReserved", mode == ConfirmMode.RE_RESERVED);
        String json = Json.write(reservation);
        if (key != null) {
            confirmations.put(key, json);
        }
        reply(exchange, 200, json);
    }

    private void onPayment(HttpExchange exchange) throws IOException {
        Call call = capture("payment", exchange);
        if (payment == Mode.DOWN) {
            reply(exchange, 503, problem("payment-session-unavailable", "Не удалось открыть оплату", 503, ""));
            return;
        }
        Map<String, Object> body = Json.parseObject(call.body());
        String order = (String) body.get("orderId");
        String json = sessions.computeIfAbsent(order, o -> {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("paymentId", UUID.randomUUID().toString());
            session.put("orderId", o);
            session.put("status", "created");
            session.put("sessionUrl", "https://localhost:8443/payment/pay/pay_" + o);
            session.put("sessionExpiresAt", body.get("sessionExpiresAt"));
            return Json.write(session);
        });
        reply(exchange, 201, json);
    }

    private Call capture(String neighbour, HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
        Call call = new Call(neighbour, exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body, headers);
        calls.add(call);
        return call;
    }

    private static String problem(String code, String title, int status, String extension) {
        return String.format(PROBLEM, code, title, status, code, extension);
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
