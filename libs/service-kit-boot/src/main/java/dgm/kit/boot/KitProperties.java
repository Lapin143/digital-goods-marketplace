package dgm.kit.boot;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

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
        @DefaultValue Probe probe,
        @DefaultValue Web web,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Redis redis,
        @DefaultValue Keycloak keycloak) {

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

    /**
     * Ограничения запроса (решение Р-29 руководства Ф4).
     *
     * @param maxBody        предел тела обычного запроса, как на шлюзе
     * @param webhookMaxBody предел тела вебхука
     */
    public record Web(@DefaultValue("2MB") DataSize maxBody, @DefaultValue("64KB") DataSize webhookMaxBody) {
    }

    /**
     * Ключи идемпотентности запросов (ADR-006).
     *
     * @param module         модуль, в базе которого работают операции с ключом, если в правиле маршрута модуль не указан; по умолчанию модуль Outbox
     * @param waitForOther   сколько повтор ждёт завершения первого запроса, пока тот выполняется
     * @param abandonedAfter через какое время незавершённый запрос считается брошенным и его ключ подхватывается
     */
    public record Idempotency(String module, @DefaultValue("2s") Duration waitForOther, @DefaultValue("60s") Duration abandonedAfter) {
    }

    /**
     * Redis (ADR-022): включается, когда задан {@code host}. Пароль берётся из секрета {@code redis_<user>}.
     *
     * @param host           адрес Redis
     * @param user           пользователь ACL (catalog, inventory, platform)
     * @param commandTimeout предел команды
     * @param connectTimeout предел подключения
     */
    public record Redis(String host, @DefaultValue("6379") int port, String user, @DefaultValue("500ms") Duration commandTimeout,
                        @DefaultValue("2s") Duration connectTimeout) {
    }

    /**
     * Admin REST Keycloak (ADR-025): включается, когда задан {@code baseUrl}.
     *
     * @param baseUrl      адрес Keycloak с префиксом, например {@code https://keycloak:8443/auth}
     * @param realm        имя realm
     * @param clientId     клиент с сервисной учётной записью
     * @param clientSecret имя секрета с паролем клиента
     * @param timeout      лимит одной операции
     */
    public record Keycloak(String baseUrl, @DefaultValue("dgm") String realm, @DefaultValue("platform-service") String clientId,
                           @DefaultValue("keycloak_client_platform") String clientSecret, @DefaultValue("2s") Duration timeout) {
    }
}
