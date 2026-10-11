package dgm.platform.identity.otp.client;

import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.service.SmsSendException;
import dgm.platform.identity.otp.service.SmsSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/**
 * Клиент провайдера SMS (компонент {@code sms-client}): {@code POST /sms/v1/messages} с ключом в заголовке {@code Authorization: Bearer}.
 * Весь вызов укладывается в {@link #CALL_LIMIT}; провайдер принял сообщение, если ответил 2xx. Номер, текст сообщения и ключ в журнал и
 * в тексты ошибок не попадают. Ключ читается из хранилища секретов при каждой отправке.
 */
public final class HttpSmsClient implements SmsSender {

    /** Лимит одного вызова провайдера. */
    public static final Duration CALL_LIMIT = Duration.ofSeconds(2);

    private final HttpClient http;
    private final URI endpoint;
    private final Supplier<SecretValue> apiKey;

    /**
     * @param base   адрес провайдера, например {@code https://external-stubs:8443}
     * @param apiKey ключ API из хранилища секретов ({@code platform_sms_key})
     * @param tls    контекст TLS, доверяющий центру сертификации проекта
     */
    public HttpSmsClient(String base, Supplier<SecretValue> apiKey, SSLContext tls) {
        Objects.requireNonNull(base, "base");
        this.endpoint = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base).resolve("/sms/v1/messages");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.http = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).connectTimeout(CALL_LIMIT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public void send(PhoneNumber phone, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", phone.value());
        body.put("text", text);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(CALL_LIMIT)
                .header("Authorization", "Bearer " + apiKey.get().reveal())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException e) {
            throw new SmsSendException("Провайдер SMS не ответил за " + CALL_LIMIT.toSeconds() + " с", e);
        } catch (IOException e) {
            throw new SmsSendException("Провайдер SMS недоступен: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsSendException("Отправка SMS прервана", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new SmsSendException("Провайдер SMS ответил кодом " + response.statusCode());
        }
    }
}
