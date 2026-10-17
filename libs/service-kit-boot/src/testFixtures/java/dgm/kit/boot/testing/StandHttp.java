package dgm.kit.boot.testing;

import dgm.kit.json.Json;
import dgm.kit.security.JwtDecoders;
import dgm.kit.security.JwtSettings;
import dgm.kit.testing.Stand;
import dgm.kit.testing.TestTokens;
import dgm.kit.tls.TlsMaterial;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Помощники интеграционных тестов сервисов на поднятом стенде (make up SET=dev-min DEBUG=1): приложение стартует в процессе
 * теста на свободных портах и подключается к PostgreSQL и Kafka стенда, а тест обращается к нему по HTTPS так, как это делают
 * соседи в сети: с клиентским сертификатом нужного сервиса или без него.
 */
public final class StandHttp {

    /** Ответ: код, тело и заголовки. */
    public record Response(int status, String body, java.net.http.HttpHeaders headers) {

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public Map<String, Object> json() {
            return Json.parseObject(body);
        }
    }

    private final HttpClient client;
    private final int port;

    private StandHttp(SSLContext context, int port) {
        this.client = HttpClient.newBuilder().sslContext(context).version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        this.port = port;
    }

    /** Клиент с сертификатом сервиса {@code service} (сервер выдаёт права по CN). */
    public static StandHttp asService(String service, int port) {
        return new StandHttp(TlsMaterial.forService(Stand.secrets(), service).mutualContext(), port);
    }

    /** Клиент без сертификата: сервер с обязательной взаимной проверкой должен оборвать рукопожатие. */
    public static StandHttp withoutCertificate(int port) {
        return new StandHttp(TlsMaterial.forService(Stand.secrets(), "api-gateway").trustOnlyContext(), port);
    }

    /** Свободный порт на петле: проверка и закрытие, затем порт отдают приложению. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Свободный порт не найден", e);
        }
    }

    /**
     * GET по HTTPS на localhost. Заголовки парами «имя, значение».
     *
     * @throws IOException рукопожатие не состоялось (например, нет клиентского сертификата) или связь прервана
     */
    public Response get(String path, String... headers) throws IOException {
        return send("GET", path, null, headers);
    }

    /** POST с телом JSON (или без тела, если {@code json} равно {@code null}); заголовки парами «имя, значение». */
    public Response post(String path, String json, String... headers) throws IOException {
        return send("POST", path, json, headers);
    }

    /** PUT с телом JSON; заголовки парами «имя, значение». */
    public Response put(String path, String json, String... headers) throws IOException {
        return send("PUT", path, json, headers);
    }

    /** DELETE без тела; заголовки парами «имя, значение». */
    public Response delete(String path, String... headers) throws IOException {
        return send("DELETE", path, null, headers);
    }

    /**
     * Запрос с произвольным методом и телом из байтов (форма multipart/form-data, файл, нестандартный Content-Type).
     *
     * @param contentType значение заголовка {@code Content-Type}; {@code null}, если тела нет
     * @param body        тело или {@code null}
     * @param headers     заголовки парами «имя, значение»
     */
    public Response request(String method, String path, String contentType, byte[] body, String... headers) throws IOException {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        return execute(method, path, publisher, contentType, headers);
    }

    private Response send(String method, String path, String json, String... headers) throws IOException {
        HttpRequest.BodyPublisher body = json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json);
        return execute(method, path, body, json == null ? null : "application/json", headers);
    }

    private Response execute(String method, String path, HttpRequest.BodyPublisher body, String contentType, String... headers)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path)).timeout(Duration.ofSeconds(15))
                .method(method, body);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            record(method, path, response);
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос прерван", e);
        }
    }

    /**
     * Запись JSON-ответа для контрактной проверки (tools/stand-checks/check_contract.py): тело, код, метод и путь без запроса
     * лежат в каталоге build/contract-samples сервиса. Каталог меняет свойство dgm.contract.samples. Сбой записи тест не роняет:
     * без образцов упадёт сама проверка, которой образцы обязательны.
     */
    private static void record(String method, String path, HttpResponse<String> response) {
        String type = response.headers().firstValue("Content-Type").orElse("");
        String body = response.body();
        if (body == null || body.isBlank() || !type.contains("json") || path.startsWith("/actuator")) {
            return;   // служебные маршруты порта управления в OpenAPI не описываются
        }
        try {
            Path dir = Path.of(System.getProperty("dgm.contract.samples", "build/contract-samples"));
            Files.createDirectories(dir);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("method", method);
            sample.put("path", path.contains("?") ? path.substring(0, path.indexOf('?')) : path);
            sample.put("status", response.statusCode());
            sample.put("body", Json.parse(body));
            String name = method + "-" + response.statusCode() + "-" + Integer.toHexString(path.hashCode()) + ".json";
            Files.writeString(dir.resolve(name), Json.write(sample), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            System.err.println("Образец ответа не записан: " + e);
        }
    }

    /** Заголовок Authorization с токеном. */
    public static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }

    /** Свойства приложения для теста: порты, каталог секретов, адреса стенда, издатель токенов тестового ключа. */
    public static void register(DynamicPropertyRegistry registry, int port, int managementPort) {
        registry.add("server.port", () -> port);
        registry.add("management.server.port", () -> managementPort);
        registry.add("dgm.probe.port", () -> 0);
        registry.add("dgm.secrets.dir", () -> Stand.secrets().toString());
        registry.add("dgm.db.host", () -> env("DGM_PG_HOST", "localhost"));
        registry.add("dgm.db.port", () -> env("DGM_PG_PORT", "15432"));
        registry.add("dgm.kafka.bootstrap", Stand::kafkaBootstrap);
        registry.add("dgm.security.jwt.issuer", () -> TestTokens.ISSUER);
        registry.add("dgm.security.jwt.jwks-uri", () -> "https://localhost:9/unused");
    }

    /** Проверка токенов, подписанных тестовым ключом: все остальные проверки (издатель, адресат, срок) настоящие. */
    public static JwtDecoder decoderFor(TestTokens tokens) {
        return JwtDecoders.forPublicKey(tokens.publicKey(), new JwtSettings(TestTokens.ISSUER, "https://localhost:9/unused"), Clock.systemUTC());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
