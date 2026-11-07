package dgm.delivery.delivery.client;

import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerCallResult;
import dgm.kit.secret.SecretValue;

/** Отправка запроса системе продавца. Интерфейс нужен, чтобы задание отправки проверялось модульными тестами без сети. */
public interface SellerCalls {

    /**
     * Один подписанный запрос.
     *
     * @param url    адрес системы продавца из подключения
     * @param secret секрет подписи этой версии подключения
     */
    SellerCallResult send(String url, SecretValue secret, PendingRequest request);
}
