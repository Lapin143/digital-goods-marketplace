package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.AttemptResult;
import dgm.delivery.delivery.domain.ProviderLookup;
import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.letter.Letter;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент провайдера почты поверх {@code java.net.http}: {@code POST /email/v1/messages} с ключом {@code delivery_email_key} в заголовке
 * {@code Authorization: Bearer}. Весь вызов укладывается в {@link #CALL_LIMIT} (5 секунд, ADR-011). Провайдер принял письмо, если ответил 2xx.
 * Ответы 400 и 422 это отказ по письму или адресу: повтор не поможет ({@link AttemptResult.Permanent}). Тайм-аут, обрыв, 429, 5xx и ошибки
 * ключа временные ({@link AttemptResult.Retryable}). Адрес, тема, текст и ключ в журнал, тексты ошибок и метрики не попадают. Ключ читается из
 * хранилища секретов при каждой отправке.
 */
public final class HttpEmailClient implements EmailClient, StatusClient {

    /** Лимит одного вызова провайдера. */
    public static final Duration CALL_LIMIT = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(HttpEmailClient.class);

    private final HttpClient http;
    private final URI endpoint;
    private final URI statusBase;
    private final Supplier<SecretValue> apiKey;

    /**
     * @param base   адрес провайдера, например {@code https://external-stubs:8443}
     * @param apiKey ключ API из хранилища секретов ({@code delivery_email_key})
     * @param tls    контекст TLS, доверяющий центру сертификации проекта
     */
    public HttpEmailClient(String base, Supplier<SecretValue> apiKey, SSLContext tls) {
        Objects.requireNonNull(base, "base");
        URI root = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
        this.endpoint = root.resolve("/email/v1/messages");
        this.statusBase = root;
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.http = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).connectTimeout(CALL_LIMIT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public AttemptResult send(UUID messageId, String to, Letter letter) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", messageId.toString());
        body.put("to", List.of(to));
        body.put("subject", letter.subject());
        body.put("text", letter.text());
        HttpResponse<Void> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(CALL_LIMIT)
                    .header("Authorization", "Bearer " + apiKey.get().reveal())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException e) {
            LOG.warn("Провайдер почты не ответил на письмо {} за {} с", messageId, CALL_LIMIT.toSeconds());
            return new AttemptResult.Retryable("provider_timeout");
        } catch (IOException e) {
            LOG.warn("Провайдер почты недоступен при отправке письма {}: {}", messageId, e.getClass().getSimpleName());
            return new AttemptResult.Retryable("provider_unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AttemptResult.Retryable("provider_interrupted");
        }
        int status = response.statusCode();
        if (status / 100 == 2) {
            return new AttemptResult.Accepted();
        }
        LOG.warn("Провайдер почты не принял письмо {}: код {}", messageId, status);
        if (status == 400 || status == 422) {
            return new AttemptResult.Permanent("address_rejected");
        }
        return new AttemptResult.Retryable(temporaryCode(status));
    }

    /** {@code GET /email/v1/messages/{id}}: статус письма. 404 это «провайдер письма не знает», остальные неудачи это «не ответил». */
    @Override
    public ProviderLookup statusOf(UUID messageId) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(statusBase.resolve("/email/v1/messages/" + messageId)).timeout(CALL_LIMIT)
                    .header("Authorization", "Bearer " + apiKey.get().reveal())
                    .header("Accept", "application/json")
                    .GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            return new ProviderLookup.Unavailable("provider_timeout");
        } catch (IOException e) {
            return new ProviderLookup.Unavailable("provider_unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProviderLookup.Unavailable("provider_interrupted");
        }
        if (response.statusCode() == 404) {
            return new ProviderLookup.Unknown();
        }
        if (response.statusCode() / 100 != 2) {
            return new ProviderLookup.Unavailable(temporaryCode(response.statusCode()));
        }
        try {
            Map<String, Object> body = Json.parseObject(response.body());
            if (!(body.get("status") instanceof String status)) {
                return new ProviderLookup.Unavailable("provider_bad_response");
            }
            return new ProviderLookup.Known(ProviderStatus.parse(status), body.get("reason") instanceof String reason ? reason : null);
        } catch (RuntimeException e) {
            return new ProviderLookup.Unavailable("provider_bad_response");
        }
    }

    private static String temporaryCode(int status) {
        if (status == 429) {
            return "provider_rate_limited";
        }
        if (status == 401 || status == 403) {
            return "provider_auth";
        }
        if (status / 100 == 5) {
            return "provider_unavailable";
        }
        return "provider_http_" + status;
    }
}
