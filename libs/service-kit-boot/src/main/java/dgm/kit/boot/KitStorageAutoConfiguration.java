package dgm.kit.boot;

import dgm.kit.storage.ObjectStorage;
import dgm.kit.storage.client.S3ObjectStorage;
import dgm.kit.storage.client.S3Settings;
import dgm.kit.tls.TlsMaterial;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Объектное хранилище документов (ADR-024). Включается, когда задан {@code dgm.storage.endpoint}: у сервисов без документов
 * хранилища нет. Соединение по TLS с проверкой цепочки нашего центра сертификации, без клиентского сертификата (хранилище входит
 * по подписи запроса и паролю из секрета). Тесты подставляют свою реализацию {@link ObjectStorage}.
 */
@AutoConfiguration(after = KitCoreAutoConfiguration.class)
@ConditionalOnProperty(prefix = "dgm.storage", name = "endpoint")
public class KitStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStorage.class)
    ObjectStorage objectStorage(KitProperties properties, SecretsDirectory secrets, TlsMaterial tls, Clock clock) {
        KitProperties.Storage storage = properties.storage();
        S3Settings settings = new S3Settings(URI.create(storage.endpoint()), storage.region(), storage.bucket(), storage.accessKey(),
                secrets.read(storage.secret()), URI.create(storage.publicBaseUrl()), storage.timeout());
        return new S3ObjectStorage(settings, tls.trustOnlyContext(), clock);
    }
}
