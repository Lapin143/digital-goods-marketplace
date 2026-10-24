package dgm.kit.boot.testing;

import dgm.kit.json.Json;
import dgm.kit.testing.Stand;
import dgm.kit.tls.TlsMaterial;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Команды управления заглушками внешних систем для тестов на стенде (шаг 4 среза 2 Ф4, Р-146): платёжный шлюз, почта и SMS получают
 * режим ответа, сбрасываются между тестами и показывают, что платформа им прислала. Заглушки: {@code tools/external-stubs},
 * административный порт 8444 (на хосте с {@code DEBUG=1} это 18444, адрес меняет переменная {@code DGM_STUBS_ADMIN}).
 *
 * <p>Режим системы заменяется целиком, поэтому тест, который его меняет, обязан вернуть умолчания. Для этого режим задают на время
 * блока: {@code admin.withPaymentMode(Map.of("scenario", "hold"), () -> { … })} возвращает умолчания даже после исключения, и чужой
 * режим не ломает следующий тест (блок {@code try-with-resources} здесь не годится: компилятор с {@code -Xlint:try -Werror} отвергает
 * ресурс, на который тело не ссылается). В начале теста вызывают {@link #reset()}.
 *
 * <p>Ошибки связи и неожиданные коды ответа заглушки превращаются в {@link StubsException} без проверяемых исключений: так тест
 * читается подряд. Клиент не знает платформы: он говорит только с административным портом заглушек (TLS без клиентского
 * сертификата, доверие нашему центру сертификации).
 */
public final class StubsAdmin {

    private static final Set<String> SYSTEMS = Set.of("payment", "email", "sms", "vkid");
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofMillis(300);

    /** Заглушка ответила не так, как ждёт тест, или не ответила. */
    public static final class StubsException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public StubsException(String message) {
            super(message);
        }

        public StubsException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final String base;
    private final HttpClient client;

    private StubsAdmin(String base, HttpClient client) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.client = client;
    }

    /** Заглушки поднятого стенда: {@code https://localhost:18444} или {@code DGM_STUBS_ADMIN}. */
    public static StubsAdmin onStand() {
        String fromEnv = System.getenv("DGM_STUBS_ADMIN");
        String url = fromEnv == null || fromEnv.isBlank() ? "https://localhost:18444" : fromEnv;
        HttpClient http = HttpClient.newBuilder()
                .sslContext(TlsMaterial.forService(Stand.secrets(), "api-gateway").trustOnlyContext())
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new StubsAdmin(url, http);
    }

    /** Заглушки по заданному адресу и клиенту: для модульных тестов этого класса. */
    public static StubsAdmin at(String baseUrl, HttpClient http) {
        return new StubsAdmin(baseUrl, http);
    }

    // ------------------------------------------------------------------------------------------------ режимы и сброс

    /** Дожидается отложенных исходов прошлого теста, очищает платежи, письма, SMS, журнал и возвращает режимы по умолчанию. */
    public Map<String, Object> reset() {
        return object(call("POST", "/admin/reset", null));
    }

    /** Ждёт, пока заглушка отправит отложенные вебхуки. */
    public Map<String, Object> idle() {
        return object(call("POST", "/admin/idle", null));
    }

    /** Заменяет режим системы целиком: поля, которых нет в {@code fields}, возвращаются к умолчанию заглушки. */
    public void setMode(String system, Map<String, ?> fields) {
        if (!SYSTEMS.contains(system)) {
            throw new IllegalArgumentException("Нет системы «" + system + "», есть " + new java.util.TreeSet<>(SYSTEMS));
        }
        call("PUT", "/admin/modes/" + system, Json.write(fields));
    }

    /** Режим платёжного шлюза на время блока: {@code scenario} (success, reject, hold, expire), {@code delayMs}, {@code create}, {@code webhook}, {@code refund}. */
    public void withPaymentMode(Map<String, ?> fields, Runnable body) {
        withMode("payment", fields, body);
    }

    /** Режим почты на время блока: {@code behavior} (accept, temp_error, perm_error), {@code failFirst}, {@code status} (delivered, failed, none), {@code webhook}. */
    public void withEmailMode(Map<String, ?> fields, Runnable body) {
        withMode("email", fields, body);
    }

    /** Режим системы на время блока; на выходе, в том числе из-за исключения, режим возвращается к умолчаниям заглушки. */
    public void withMode(String system, Map<String, ?> fields, Runnable body) {
        supplyInMode(system, fields, () -> {
            body.run();
            return null;
        });
    }

    /** То же, когда блок возвращает результат (отдельное имя: перегрузка с {@code Supplier} не проходит {@code -Xlint:overloads -Werror}). */
    public <T> T supplyInMode(String system, Map<String, ?> fields, Supplier<T> body) {
        setMode(system, fields);
        try {
            return body.get();
        } finally {
            setMode(system, Map.of());
        }
    }

    // ------------------------------------------------------------------------------------------------ платежи

    public List<Map<String, Object>> payments() {
        return items(call("GET", "/admin/payments", null));
    }

    public Map<String, Object> payment(String paymentId) {
        return object(call("GET", "/admin/payments/" + path(paymentId), null));
    }

    /** Первый платёж, для которого {@code match} истинно; ждёт, пока платёж появится после вызова платформы. */
    public Map<String, Object> awaitPayment(Predicate<Map<String, Object>> match, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            for (Map<String, Object> payment : payments()) {
                if (match.test(payment)) {
                    return payment;
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new StubsException("Платёж не появился в заглушке за " + wait.toSeconds() + " с");
            }
            pause();
        }
    }

    /** Платёж заказа {@code orderId}: ждёт его появления. */
    public Map<String, Object> awaitPaymentOfOrder(String orderId, Duration wait) {
        return awaitPayment(p -> orderId.equals(p.get("orderId")), wait);
    }

    /** Исход «оплачен» по команде: поздняя оплата, оплата после отказа. {@code overrides}: repeat, parallel, badSignature, timestampOffsetSeconds, amountDelta, send. */
    public Map<String, Object> confirmPayment(String paymentId, Map<String, ?> overrides) {
        return paymentCommand(paymentId, "confirm", overrides);
    }

    public Map<String, Object> declinePayment(String paymentId, Map<String, ?> overrides) {
        return paymentCommand(paymentId, "decline", overrides);
    }

    public Map<String, Object> expirePayment(String paymentId, Map<String, ?> overrides) {
        return paymentCommand(paymentId, "expire", overrides);
    }

    /** Ручной возврат в кабинете шлюза: возврат помечается выполненным, уходит вебхук {@code refund.completed}. */
    public Map<String, Object> completeRefund(String paymentId, Map<String, ?> overrides) {
        return paymentCommand(paymentId, "refund-complete", overrides);
    }

    /** Уведомление заданного типа без смены статуса платежа: повтор, перестановка, неизвестный тип (например, {@code payment.paid}). */
    public Map<String, Object> resendWebhook(String paymentId, String eventType, Map<String, ?> overrides) {
        Map<String, Object> body = new LinkedHashMap<>(overrides);
        body.put("type", eventType);
        return paymentCommand(paymentId, "webhook", body);
    }

    private Map<String, Object> paymentCommand(String paymentId, String action, Map<String, ?> body) {
        return object(call("POST", "/admin/payments/" + path(paymentId) + "/" + action, Json.write(body)));
    }

    // ------------------------------------------------------------------------------------------------ письма и SMS

    /** Письма по условию; пустое значение условия не учитывается. */
    public List<Map<String, Object>> emails(String to, String sender, String subject) {
        StringBuilder query = new StringBuilder();
        append(query, "to", to);
        append(query, "sender", sender);
        append(query, "subject", subject);
        return items(call("GET", "/admin/emails" + query, null));
    }

    /** Ждёт, пока писем по условию станет не меньше {@code count}; не дождалась: {@link StubsException} с тем, что нашлось. */
    public List<Map<String, Object>> awaitEmails(int count, Duration wait, String to, String sender, String subject) {
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            List<Map<String, Object>> found = emails(to, sender, subject);
            if (found.size() >= count) {
                return found;
            }
            if (System.nanoTime() >= deadline) {
                throw new StubsException("Писем (to=" + to + ", sender=" + sender + ", subject=" + subject + ") найдено " + found.size()
                        + " из " + count + " за " + wait.toSeconds() + " с");
            }
            pause();
        }
    }

    /** Статус письма вебхуком: accepted, delivered или failed. */
    public Map<String, Object> emailStatus(String messageId, String status) {
        return object(call("POST", "/admin/emails/" + path(messageId) + "/status", Json.write(Map.of("status", status))));
    }

    /**
     * Статус письма с полями команды: {@code reason} ({@code address_rejected} или {@code other}, для {@code failed}) и поля вебхука
     * ({@code send = false} меняет статус письма у провайдера и вебхука не шлёт: платформа узнаёт о нём только опросом).
     */
    public Map<String, Object> emailStatus(String messageId, String status, Map<String, ?> fields) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(fields);
        body.put("status", status);
        return object(call("POST", "/admin/emails/" + path(messageId) + "/status", Json.write(body)));
    }

    /** Сообщения «телефона» номера {@code to}: доставленные, а с {@code everything} все принятые. */
    public List<Map<String, Object>> sms(String to, boolean everything) {
        StringBuilder query = new StringBuilder();
        append(query, "to", to);
        append(query, "all", everything ? "1" : null);
        return items(call("GET", "/admin/sms" + query, null));
    }

    // ------------------------------------------------------------------------------------------------ вспомогательное

    private String call(String method, String path, String json) {
        HttpRequest.BodyPublisher body = json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(CALL_TIMEOUT).method(method, body)
                .header("Accept", "application/json");
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                String text = response.body() == null ? "" : response.body();
                throw new StubsException(method + " " + path + ": код " + response.statusCode() + ": " + text.substring(0, Math.min(300, text.length())));
            }
            return response.body();
        } catch (IOException e) {
            throw new StubsException(method + " " + path + ": заглушки недоступны (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StubsException(method + " " + path + ": ожидание прервано", e);
        }
    }

    private static Map<String, Object> object(String body) {
        return body == null || body.isBlank() ? Map.of() : Json.parseObject(body);
    }

    private static List<Map<String, Object>> items(String body) {
        Object parsed = Json.parse(body);
        Object list = parsed instanceof Map<?, ?> map ? map.get("items") : parsed;
        List<Map<String, Object>> result = new ArrayList<>();
        if (list instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> row) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : row.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    result.add(copy);
                }
            }
        }
        return result;
    }

    private static void append(StringBuilder query, String name, String value) {
        if (value != null && !value.isEmpty()) {
            query.append(query.isEmpty() ? '?' : '&').append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
    }

    private static String path(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void pause() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StubsException("Ожидание прервано", e);
        }
    }
}
