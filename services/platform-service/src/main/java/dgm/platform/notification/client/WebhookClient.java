package dgm.platform.notification.client;

import dgm.kit.client.EndpointGuard;
import dgm.kit.client.SellerCaller;
import dgm.kit.secret.SecretValue;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.WebhookCallResult;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент webhook продавцу (компонент {@code seller-webhook-job}, Р-193): один подписанный {@code POST} через {@link SellerCaller} (проверка адреса перед
 * каждым вызовом, подпись и метка времени, тайм-аут 5 секунд, ответ до 64 КБ, без перенаправлений). Тело это байты из базы, заголовок
 * {@code X-Event-Id} одинаков во всех попытках: по нему продавец отсекает дубли. Повторы и состояние ведёт вызывающий. В журнал идут идентификаторы,
 * адрес продавца и тело ответа не попадают.
 */
public final class WebhookClient implements WebhookCalls {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookClient.class);

    private final SellerCaller caller;

    public WebhookClient(SellerCaller caller) {
        this.caller = caller;
    }

    @Override
    public WebhookCallResult send(String url, SecretValue secret, PendingWebhook webhook) {
        byte[] body = webhook.payload().getBytes(StandardCharsets.UTF_8);
        try {
            SellerCaller.Response response = caller.post(url, body, secret,
                    Map.of("X-Event-Id", webhook.eventId().toString(), "X-Event-Type", webhook.eventType()));
            if (response.status() < 100 || response.status() > 599) {
                // код вне диапазона HTTP база не примет (ck_webhook_delivery_last_response_code): считаем ответ неразборчивым
                return WebhookCallResult.failed(null, "seller_protocol");
            }
            if (response.success()) {
                return WebhookCallResult.ok(response.status());
            }
            LOG.warn("Система продавца {} ответила {} на уведомление {}", webhook.sellerId(), response.status(), webhook.id());
            return WebhookCallResult.failed(response.status(), "seller_http_" + response.status());
        } catch (EndpointGuard.RejectedException e) {
            LOG.warn("Адрес системы продавца {} не прошёл проверку перед уведомлением {}", webhook.sellerId(), webhook.id());
            return WebhookCallResult.failed(null, "endpoint_rejected");
        } catch (SellerCaller.CallException e) {
            LOG.warn("Уведомление {} продавцу {} не отправлено: {}", webhook.id(), webhook.sellerId(), e.kind());
            return WebhookCallResult.failed(null, "seller_" + e.kind().name().toLowerCase(Locale.ROOT));
        }
    }
}
