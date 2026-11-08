package dgm.platform.notification.client;

import dgm.kit.secret.SecretValue;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.WebhookCallResult;

/** Отправка webhook системе продавца. Интерфейс нужен, чтобы отправитель проверялся модульными тестами без сети. */
public interface WebhookCalls {

    /**
     * Один подписанный запрос.
     *
     * @param url    адрес системы продавца из подключения
     * @param secret секрет подписи этой версии подключения
     */
    WebhookCallResult send(String url, SecretValue secret, PendingWebhook webhook);
}
