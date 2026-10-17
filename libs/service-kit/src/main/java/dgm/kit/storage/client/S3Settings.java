package dgm.kit.storage.client;

import dgm.kit.secret.SecretValue;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Подключение к бакету S3.
 *
 * @param endpoint   адрес хранилища, из которого сервис пишет и удаляет объекты: {@code https://object-storage:9000}, адресация по пути
 * @param region     регион для подписи; хранилищу всё равно, но подпись его содержит
 * @param bucket     бакет документов
 * @param accessKey  имя учётной записи сервиса в хранилище
 * @param secretKey  пароль учётной записи
 * @param publicBase адрес, под которым ссылки открывает пользователь: шлюз с префиксом {@code /files}, например
 *                   {@code https://api.marketplace.example/files}. Заголовок {@code Host} входит в подпись, поэтому ссылка действует
 *                   только по этому адресу, а шлюз обязан передать хранилищу Host без изменений и убрать префикс
 * @param timeout    предел ожидания ответа хранилища
 */
public record S3Settings(URI endpoint, String region, String bucket, String accessKey, SecretValue secretKey, URI publicBase,
                         Duration timeout) {

    public S3Settings {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(accessKey, "accessKey");
        Objects.requireNonNull(secretKey, "secretKey");
        Objects.requireNonNull(publicBase, "publicBase");
        Objects.requireNonNull(timeout, "timeout");
        if (!"https".equals(endpoint.getScheme())) {
            throw new IllegalArgumentException("Хранилище доступно только по TLS (ADR-022), а адрес " + endpoint.getScheme() + "://...");
        }
        if (bucket.isBlank() || bucket.contains("/")) {
            throw new IllegalArgumentException("Имя бакета пустое или содержит «/»");
        }
        if (publicBase.getHost() == null) {
            throw new IllegalArgumentException("В адресе для ссылок нет имени узла");
        }
    }
}
