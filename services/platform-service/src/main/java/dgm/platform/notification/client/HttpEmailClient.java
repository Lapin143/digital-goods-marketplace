package dgm.platform.notification.client;

import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import dgm.platform.notification.service.EmailSendException;
import dgm.platform.notification.service.EmailSender;
import dgm.platform.notification.service.OutgoingEmail;
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
 * Клиент провайдера почты (компонент {@code email-sender}): {@code POST /email/v1/messages} с ключом в заголовке
 * {@code Authorization: Bearer}. Весь вызов укладывается в {@link #CALL_LIMIT}; провайдер принял письмо, если ответил 2xx. Идентификатор
 * сообщения (поле {@code messageId}) равен идентификатору уведомления: повторная отправка того же письма после сбоя между отправкой и
 * записью результата провайдера не дублируется. Ответы 400 и 422 это отказ провайдера по письму или адресу, повтор не поможет; таймаут,
 * обрыв, 429, 5xx и ошибки ключа считаются временными. Адрес, тема, текст и ключ в журнал и в тексты ошибок не попадают. Ключ читается
 * из хранилища секретов при каждой отправке.
 */
public final class HttpEmailClient implements EmailSender {

    /** Лимит одного вызова провайдера. */
    public static final Duration CALL_LIMIT = Duration.ofSeconds(2);

    private final HttpClient http;
    private final URI endpoint;
    private final Supplier<SecretValue> apiKey;

    /**
     * @param base   адрес провайдера, например {@code https://external-stubs:8443}
     * @param apiKey ключ API из хранилища секретов ({@code platform_email_key})
     * @param tls    контекст TLS, доверяющий центру сертификации проекта
     */
    public HttpEmailClient(String base, Supplier<SecretValue> apiKey, SSLContext tls) {
        Objects.requireNonNull(base, "base");
        this.endpoint = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base).resolve("/email/v1/messages");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.http = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).connectTimeout(CALL_LIMIT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public void send(OutgoingEmail mail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", mail.messageId());
        body.put("to", mail.to());
        body.put("subject", mail.subject());
        body.put("text", mail.text());
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(CALL_LIMIT)
                .header("Authorization", "Bearer " + apiKey.get().reveal())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException e) {
            throw new EmailSendException("provider_timeout", false, "Провайдер почты не ответил за " + CALL_LIMIT.toSeconds() + " с", e);
        } catch (IOException e) {
            throw new EmailSendException("provider_unavailable", false, "Провайдер почты недоступен: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailSendException("provider_interrupted", false, "Отправка письма прервана", e);
        }
        int status = response.statusCode();
        if (status / 100 == 2) {
            return;
        }
        if (status == 400 || status == 422) {
            throw new EmailSendException("address_rejected", true, "Провайдер почты отверг письмо: " + status);
        }
        throw new EmailSendException(temporaryCode(status), false, "Провайдер почты ответил кодом " + status);
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
