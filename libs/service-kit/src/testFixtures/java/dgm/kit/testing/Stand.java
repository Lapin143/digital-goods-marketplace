package dgm.kit.testing;

import dgm.kit.secret.SecretFiles;
import dgm.kit.tls.TlsMaterial;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Доступ интеграционных тестов к поднятому стенду (make up SET=dev-min DEBUG=1): PostgreSQL на 127.0.0.1:15432 с проверкой
 * сертификата, Kafka на 127.0.0.1:19093 с клиентским сертификатом сервиса. Секреты берутся из каталога {@code DGM_SECRETS_DIR}
 * (по умолчанию {@code secrets/} репозитория). Адреса меняются переменными {@code DGM_PG_HOST}, {@code DGM_PG_PORT},
 * {@code DGM_KAFKA_BOOTSTRAP}, {@code DGM_STORAGE_ENDPOINT}. Объектное хранилище (набор {@code storage}) отвечает по TLS на
 * 127.0.0.1:19000 и имени {@code localhost}.
 */
public final class Stand {

    private Stand() {
    }

    public static Path secrets() {
        return SecretFiles.directory();
    }

    public static String kafkaBootstrap() {
        return env("DGM_KAFKA_BOOTSTRAP", "localhost:19093");
    }

    /** Адрес S3 API объектного хранилища с хоста; отладочный порт открывает {@code DEBUG=1}. */
    public static String storageEndpoint() {
        return env("DGM_STORAGE_ENDPOINT", "https://localhost:19000");
    }

    /** Настройки клиента Kafka от имени сервиса (сертификат с этим CN, права Kafka выданы по CN). */
    public static Map<String, Object> kafkaSecurity(String service) {
        return TlsMaterial.forService(secrets(), service).kafkaProperties();
    }

    /** Подключение к базе под рабочей ролью (пароль из секрета {@code db_<роль>}), TLS с проверкой имени узла. */
    public static DataSource dataSource(String role, String database) {
        TlsMaterial tls = TlsMaterial.forService(secrets(), "none");
        String url = tls.jdbcUrl(env("DGM_PG_HOST", "localhost"), Integer.parseInt(env("DGM_PG_PORT", "15432")), database);
        return new DriverManagerDataSource(url, role, SecretFiles.read("db_" + role).reveal());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
