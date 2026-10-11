package dgm.kit.redis;

import java.time.Duration;

/**
 * Подключение к Redis. Пароль в настройки не входит: клиент получает его отдельно из секрета, чтобы он не попал в {@code toString}.
 *
 * @param host           адрес Redis
 * @param port           порт TLS
 * @param user           пользователь ACL (gateway, catalog, inventory, platform)
 * @param commandTimeout предел одной команды: дольше клиент отказывает, не удерживая запрос пользователя
 * @param connectTimeout предел установки соединения
 */
public record RedisSettings(String host, int port, String user, Duration commandTimeout, Duration connectTimeout) {

    public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMillis(500);
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    public RedisSettings {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Не задан адрес Redis");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("Не задан пользователь Redis");
        }
        if (commandTimeout == null || commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("Предел команды Redis должен быть положительным");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("Предел подключения к Redis должен быть положительным");
        }
    }
}
