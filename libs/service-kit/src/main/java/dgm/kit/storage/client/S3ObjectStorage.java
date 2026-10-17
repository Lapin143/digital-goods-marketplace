package dgm.kit.storage.client;

import dgm.kit.problem.ProblemException;
import dgm.kit.storage.ObjectStorage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Хранилище S3 по HTTPS: запись и удаление объектов запросами с подписью в заголовке, ссылки на скачивание с подписью в строке запроса.
 * Адресация по пути ({@code /бакет/ключ}), TLS с проверкой цепочки нашего центра сертификации и имени узла (ADR-022, ADR-024).
 *
 * <p>Ключ объекта состоит из латиницы, цифр и {@code - _ . ~ /}; другие знаки кодируются, но в ключи сервисов попадают только
 * идентификаторы UUID, текст пользователя в ключ не входит.
 */
public final class S3ObjectStorage implements ObjectStorage {

    private static final Logger LOG = LoggerFactory.getLogger(S3ObjectStorage.class);
    private static final int MAX_LOGGED_BODY = 300;

    private final S3Settings settings;
    private final HttpClient http;
    private final Clock clock;
    private final SigV4 signer;

    public S3ObjectStorage(S3Settings settings, SSLContext tls, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signer = new SigV4(settings.accessKey(), settings.secretKey(), settings.region());
        this.http = HttpClient.newBuilder().sslContext(Objects.requireNonNull(tls, "tls")).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.timeout()).build();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Objects.requireNonNull(content, "content");
        if (contentType == null || contentType.isBlank() || contentType.indexOf('\n') >= 0 || contentType.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Тип содержимого пуст или содержит перевод строки");
        }
        URI uri = objectUri(key);
        Instant now = clock.instant();
        String payloadHash = SigV4.sha256Hex(content);
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("content-type", contentType);
        signed.put("host", hostOf(uri));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", SigV4.stamp(now));
        String authorization = signer.authorization("PUT", canonicalPath(key), signed, payloadHash, now);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.timeout())
                .header("Content-Type", contentType)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", SigV4.stamp(now))
                .header("Authorization", authorization)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build();
        HttpResponse<String> response = send(request, "запись");
        if (response.statusCode() / 100 != 2) {
            throw failed("запись", response);
        }
    }

    @Override
    public void delete(String key) {
        URI uri = objectUri(key);
        Instant now = clock.instant();
        String payloadHash = SigV4.sha256Hex(new byte[0]);
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("host", hostOf(uri));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", SigV4.stamp(now));
        String authorization = signer.authorization("DELETE", canonicalPath(key), signed, payloadHash, now);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(settings.timeout())
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", SigV4.stamp(now))
                .header("Authorization", authorization)
                .DELETE().build();
        HttpResponse<String> response = send(request, "удаление");
        int status = response.statusCode();
        if (status / 100 != 2 && status != 404) {
            throw failed("удаление", response);
        }
    }

    @Override
    public PresignedLink presignGet(String key, Duration ttl) {
        long seconds = ttl.toSeconds();
        Instant now = clock.instant();
        URI base = settings.publicBase();
        String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        String query = signer.presignedQuery("GET", hostOf(base), canonicalPath(key), now, seconds);
        String port = base.getPort() == -1 ? "" : ":" + base.getPort();
        URI url = URI.create(base.getScheme() + "://" + base.getHost() + port + basePath + canonicalPath(key) + "?" + query);
        return new PresignedLink(url, now.plusSeconds(seconds));
    }

    /** Путь, каким его видит хранилище и какой входит в подпись: {@code /бакет/ключ}. */
    private String canonicalPath(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")) {
            throw new IllegalArgumentException("Ключ объекта пуст или начинается с «/»");
        }
        return "/" + settings.bucket() + "/" + SigV4.encodePath(key);
    }

    private URI objectUri(String key) {
        String base = settings.endpoint().toString().replaceAll("/+$", "");
        return URI.create(base + canonicalPath(key));
    }

    /** Значение заголовка Host, которое отправит клиент: имя и порт, если он указан в адресе (Java убирает стандартный порт сам). */
    private static String hostOf(URI uri) {
        int port = uri.getPort();
        boolean standard = port == -1 || "https".equals(uri.getScheme()) && port == 443 || "http".equals(uri.getScheme()) && port == 80;
        return standard ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private HttpResponse<String> send(HttpRequest request, String action) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("Хранилище документов: {} не выполнено: {}", action, e.toString());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private ProblemException failed(String action, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        LOG.error("Хранилище документов: {} отвергнуто, код {}: {}", action, response.statusCode(),
                body.length() > MAX_LOGGED_BODY ? body.substring(0, MAX_LOGGED_BODY) : body);
        return unavailable();
    }

    private static ProblemException unavailable() {
        return ProblemException.dependencyUnavailable("Хранилище документов временно недоступно, повторите позже.", 5);
    }
}
