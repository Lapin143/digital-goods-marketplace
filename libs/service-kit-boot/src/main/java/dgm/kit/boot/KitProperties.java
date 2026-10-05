package dgm.kit.boot;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки каркаса, префикс {@code dgm}. Значения по умолчанию лежат в {@code dgm-kit-defaults.properties}, сервис задаёт своё имя,
 * базу и модули в {@code application.yml}, а адреса и каталог секретов получает от Compose.
 *
 * @param service  имя сервиса: CN его сертификата, группа Kafka, источник событий
 * @param routes   файл правил маршрутов в classpath, его создаёт tools/docs-checks/gen_routes.py из OpenAPI
 */
@ConfigurationProperties(prefix = "dgm")
public record KitProperties(
        String service,
        @DefaultValue("dgm/routes.json") String routes,
        @DefaultValue Security security,
        @DefaultValue Db db,
        @DefaultValue Kafka kafka,
        @DefaultValue Outbox outbox,
        @DefaultValue Consumer consumer,
        @DefaultValue Probe probe) {

    /** Проверка токена. */
    public record Security(@DefaultValue Jwt jwt) {
    }

    /**
     * @param issuer  издатель токена (адрес realm для браузера)
     * @param jwksUri адрес ключей Keycloak внутри сети
     * @param audience адресат токена
     */
    public record Jwt(String issuer, String jwksUri, @DefaultValue("dgm-api") String audience) {
    }

    /**
     * База сервиса.
     *
     * @param database    имя базы: пусто, если у сервиса базы нет
     * @param migratorRole роль-мигратор (владелец базы), работает только при старте
     * @param locations   каталоги миграций Flyway; в тестовом профиле к ним добавляется каталог тестовых данных
     * @param modules     рабочие роли модулей: имя модуля и роль с размером пула
     */
    public record Db(
            @DefaultValue("postgres") String host,
            @DefaultValue("5432") int port,
            String database,
            String migratorRole,
            @DefaultValue("true") boolean migrate,
            @DefaultValue("classpath:db/migration") List<String> locations,
            @DefaultValue Map<String, DbModule> modules) {
    }

    /**
     * @param role     рабочая роль модуля (секрет {@code db_<роль>})
     * @param poolSize размер пула: предел соединений роли минус одно соединение публикатора Outbox
     */
    public record DbModule(String role, @DefaultValue("4") int poolSize) {
    }

    /** @param bootstrap адрес брокера; пусто: сервис к Kafka не подключается */
    public record Kafka(String bootstrap) {
    }

    /** @param module модуль, под ролью которого публикатор читает таблицу outbox */
    public record Outbox(@DefaultValue("true") boolean enabled, String module) {
    }

    /** @param module модуль, в базе которого идёт запись обработанных событий и работают обработчики */
    public record Consumer(@DefaultValue("true") boolean enabled, String module) {
    }

    /** @param port порт проверки готовности на 127.0.0.1 внутри контейнера (0: свободный) */
    public record Probe(@DefaultValue("true") boolean enabled, @DefaultValue("8081") int port) {
    }
}
