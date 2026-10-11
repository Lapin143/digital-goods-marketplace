package dgm.kit.keycloak;

import dgm.kit.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент Admin REST Keycloak для платформы (компонент {@code keycloak-admin-client}, ADR-025): чтение пользователей постранично,
 * атрибуты, роли realm, отключение, учётные данные.
 *
 * <p>Токен берётся по client credentials клиента {@code platform-service}, хранится до истечения (с запасом 30 секунд) и обновляется
 * сам; секрет клиента читается через {@code Supplier} при каждом получении токена. Каждая операция укладывается в лимит
 * {@link #CALL_LIMIT} (2 секунды, включая получение токена): дольше платформа не ждёт, потому что вызов идёт из запроса пользователя
 * или из сверки, а недоставленное изменение повторит {@code identity-reconciler}. Операции идемпотентны: повторное добавление роли,
 * атрибута или отключения ничего не меняет, удаление отсутствующего не ошибка. Тела ответов, токены и адреса почты в журнал и в тексты
 * ошибок не попадают.
 */
public final class KeycloakAdminClient {

    /** Лимит одной операции вместе с получением токена. */
    public static final Duration CALL_LIMIT = Duration.ofSeconds(2);

    private static final Logger LOG = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final Duration TOKEN_SKEW = Duration.ofSeconds(30);

    private final HttpClient http;
    private final String base;
    private final String realm;
    private final String clientId;
    private final Supplier<String> clientSecret;
    private final Clock clock;
    private final Duration limit;

    private String token;
    private Instant tokenExpiresAt = Instant.MIN;

    /**
     * @param base         адрес Keycloak с префиксом, например {@code https://keycloak:8443/auth}
     * @param realm        имя realm
     * @param clientId     клиент с сервисной учётной записью (роли realm-management)
     * @param clientSecret секрет клиента из хранилища секретов
     * @param tls          контекст TLS, доверяющий центру сертификации проекта (Keycloak клиентского сертификата не требует)
     */
    public KeycloakAdminClient(String base, String realm, String clientId, Supplier<String> clientSecret, SSLContext tls, Clock clock,
                               Duration limit) {
        this.base = trimSlash(Objects.requireNonNull(base, "base"));
        this.realm = Objects.requireNonNull(realm, "realm");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limit = Objects.requireNonNull(limit, "limit");
        this.http = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).connectTimeout(limit)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public KeycloakAdminClient(String base, String realm, String clientId, Supplier<String> clientSecret, SSLContext tls, Clock clock) {
        this(base, realm, clientId, clientSecret, tls, clock, CALL_LIMIT);
    }

    // ---- Пользователи ----

    /** Число пользователей realm. */
    public int countUsers() {
        Object count = Json.parse(call("GET", "/users/count", null, 200).body());
        if (count instanceof Number n) {
            return n.intValue();
        }
        throw new KeycloakException(200, "Keycloak вернул число пользователей в неожиданном виде", false);
    }

    /**
     * Страница пользователей. Постраничное чтение не держит все записи в памяти: сверка читает страницами по {@code max}, пока не
     * получит страницу короче.
     *
     * @param first сколько записей пропустить
     * @param max   размер страницы, от 1 до 1000
     */
    public List<KeycloakUser> listUsers(int first, int max) {
        if (first < 0 || max < 1 || max > 1000) {
            throw new IllegalArgumentException("Страница пользователей: first от 0, max от 1 до 1000");
        }
        Object parsed = Json.parse(call("GET", "/users?first=" + first + "&max=" + max + "&briefRepresentation=false", null, 200).body());
        List<KeycloakUser> users = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                users.add(user(item));
            }
        }
        return users;
    }

    /** Пользователь по идентификатору; пусто, если его нет. */
    public Optional<KeycloakUser> findUser(String userId) {
        try {
            return Optional.of(user(Json.parse(call("GET", "/users/" + segment(userId), null, 200).body())));
        } catch (KeycloakException e) {
            if (e.notFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Задаёт атрибуты пользователя, остальные не трогает. Keycloak заменяет атрибуты целиком (и стирает не присланные поля профиля),
     * поэтому читается полное представление пользователя, меняется и отправляется обратно целиком. Повтор с теми же значениями ничего не
     * меняет и Keycloak не вызывает. Значение {@code null} у ключа удаляет атрибут.
     */
    public void setAttributes(String userId, Map<String, String> changes) {
        Map<String, Object> user = rawUser(userId);
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (user.get("attributes") instanceof Map<?, ?> existing) {
            existing.forEach((name, value) -> attributes.put(String.valueOf(name), value));
        }
        boolean changed = false;
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (change.getValue() == null) {
                if (attributes.remove(change.getKey()) != null) {
                    changed = true;
                }
            } else {
                List<String> wanted = List.of(change.getValue());
                if (!wanted.equals(attributes.get(change.getKey()))) {
                    attributes.put(change.getKey(), wanted);
                    changed = true;
                }
            }
        }
        if (changed) {
            user.put("attributes", attributes);
            call("PUT", "/users/" + segment(userId), Json.write(user), 204);
        }
    }

    /** Включает или отключает учётную запись; если состояние уже такое, Keycloak не вызывается. */
    public void setEnabled(String userId, boolean enabled) {
        Map<String, Object> user = rawUser(userId);
        if (Boolean.valueOf(enabled).equals(user.get("enabled"))) {
            return;
        }
        user.put("enabled", enabled);
        call("PUT", "/users/" + segment(userId), Json.write(user), 204);
    }

    /**
     * Добавляет пользователю обязательное действие входа, например {@code CONFIGURE_TOTP}: при следующем входе Keycloak потребует его
     * выполнить. Если действие уже назначено, Keycloak не вызывается.
     */
    public void addRequiredAction(String userId, String action) {
        Map<String, Object> user = rawUser(userId);
        List<Object> actions = new ArrayList<>();
        if (user.get("requiredActions") instanceof List<?> existing) {
            actions.addAll(existing);
        }
        if (actions.contains(action)) {
            return;
        }
        actions.add(action);
        user.put("requiredActions", actions);
        call("PUT", "/users/" + segment(userId), Json.write(user), 204);
    }

    /** Завершает все сессии пользователя. Если пользователя нет, ничего не происходит. */
    public void logout(String userId) {
        try {
            call("POST", "/users/" + segment(userId) + "/logout", null, 204);
        } catch (KeycloakException e) {
            if (!e.notFound()) {
                throw e;
            }
        }
    }

    /** Удаляет пользователя вместе с сессиями и учётными данными. Если его уже нет, это не ошибка. */
    public void deleteUser(String userId) {
        try {
            call("DELETE", "/users/" + segment(userId), null, 204);
        } catch (KeycloakException e) {
            if (!e.notFound()) {
                throw e;
            }
        }
    }

    /** Псевдонимы внешних поставщиков входа, с которыми связан пользователь (VK ID); пусто, если он вошёл только паролем. */
    public List<String> federatedProviders(String userId) {
        Object parsed = Json.parse(call("GET", "/users/" + segment(userId) + "/federated-identity", null, 200).body());
        List<String> providers = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> link && link.get("identityProvider") != null) {
                    providers.add(String.valueOf(link.get("identityProvider")));
                }
            }
        }
        return providers;
    }

    private Map<String, Object> rawUser(String userId) {
        return Json.parseObject(call("GET", "/users/" + segment(userId), null, 200).body());
    }

    // ---- Роли realm ----

    /** Имена ролей realm, назначенных пользователю непосредственно. */
    public List<String> realmRoles(String userId) {
        return roleNames("/users/" + segment(userId) + "/role-mappings/realm");
    }

    /**
     * Назначает роль realm. Представление роли берётся из списка ролей, доступных этому пользователю для назначения: так хватает прав
     * {@code manage-users}, а чтение роли по имени ({@code GET /roles/{имя}}) требует {@code view-realm}, которого у служебной записи нет
     * (Keycloak отвечает 403, находка шага 9 Ф4). Если роль у пользователя уже есть, прямо или через составную, ничего не меняется.
     */
    public void addRealmRole(String userId, String roleName) {
        String mappings = "/users/" + segment(userId) + "/role-mappings/realm";
        Map<String, Object> role = findRole(mappings + "/available", roleName);
        if (role != null) {
            call("POST", mappings, Json.write(List.of(role)), 204);
            return;
        }
        if (!effectiveRealmRoles(userId).contains(roleName)) {
            throw new KeycloakException(404, "В realm нет роли " + roleName, false);
        }
    }

    /** Снимает прямое назначение роли realm. Если роль не назначена напрямую, ничего не меняется. */
    public void removeRealmRole(String userId, String roleName) {
        String mappings = "/users/" + segment(userId) + "/role-mappings/realm";
        Map<String, Object> role = findRole(mappings, roleName);
        if (role != null) {
            call("DELETE", mappings, Json.write(List.of(role)), 204);
        }
    }

    /** Роли, действующие у пользователя: назначенные напрямую и входящие в составные. */
    public List<String> effectiveRealmRoles(String userId) {
        return roleNames("/users/" + segment(userId) + "/role-mappings/realm/composite");
    }

    // ---- Учётные данные ----

    /** Типы учётных данных пользователя ({@code password}, {@code otp} и другие), по одному на каждое. */
    public List<String> credentialTypes(String userId) {
        Object parsed = Json.parse(call("GET", "/users/" + segment(userId) + "/credentials", null, 200).body());
        List<String> types = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> credential && credential.get("type") != null) {
                    types.add(String.valueOf(credential.get("type")));
                }
            }
        }
        return types;
    }

    /** У пользователя есть учётные данные этого типа. */
    public boolean hasCredential(String userId, String type) {
        return credentialTypes(userId).contains(type);
    }

    /** Удаляет учётные данные по идентификатору; отсутствующие не ошибка. */
    public void deleteCredential(String userId, String credentialId) {
        try {
            call("DELETE", "/users/" + segment(userId) + "/credentials/" + segment(credentialId), null, 204);
        } catch (KeycloakException e) {
            if (!e.notFound()) {
                throw e;
            }
        }
    }

    // ---- Внутреннее ----

    /** Имена ролей из ответа Keycloak по пути {@code path} (список представлений ролей). */
    private List<String> roleNames(String path) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> role : roles(path)) {
            names.add(String.valueOf(role.get("name")));
        }
        return names;
    }

    /** Представление роли ({@code id} и {@code name}) с заданным именем из списка по пути {@code path}; нет такой роли, {@code null}. */
    private Map<String, Object> findRole(String path, String roleName) {
        for (Map<String, Object> role : roles(path)) {
            if (roleName.equals(role.get("name"))) {
                Map<String, Object> representation = new LinkedHashMap<>();
                representation.put("id", role.get("id"));
                representation.put("name", role.get("name"));
                return representation;
            }
        }
        return null;
    }

    private List<Map<String, Object>> roles(String path) {
        Object parsed = Json.parse(call("GET", path, null, 200).body());
        List<Map<String, Object>> roles = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> role && role.get("name") != null && role.get("id") != null) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    role.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    roles.add(copy);
                }
            }
        }
        return roles;
    }

    private static KeycloakUser user(Object item) {
        if (!(item instanceof Map<?, ?> map) || map.get("id") == null) {
            throw new KeycloakException(200, "Keycloak вернул пользователя в неожиданном виде", false);
        }
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        if (map.get("attributes") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                List<String> values = new ArrayList<>();
                if (entry.getValue() instanceof List<?> list) {
                    for (Object v : list) {
                        values.add(String.valueOf(v));
                    }
                }
                attributes.put(String.valueOf(entry.getKey()), values);
            }
        }
        long created = map.get("createdTimestamp") instanceof Number n ? n.longValue() : 0L;
        return new KeycloakUser(String.valueOf(map.get("id")), text(map.get("username")), text(map.get("email")), text(map.get("firstName")),
                text(map.get("lastName")), Boolean.TRUE.equals(map.get("enabled")), Boolean.TRUE.equals(map.get("emailVerified")), created,
                attributes);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Один вызов Admin REST в пределах лимита времени; при 401 токен обновляется и вызов повторяется один раз. */
    private HttpResponse<String> call(String method, String path, String body, int expectedStatus) {
        Instant deadline = clock.instant().plus(limit);
        HttpResponse<String> response = exchange(method, path, body, deadline);
        if (response.statusCode() == 401) {
            invalidateToken();
            response = exchange(method, path, body, deadline);
        }
        int status = response.statusCode();
        if (status == expectedStatus || (expectedStatus == 204 && status == 200)) {
            return response;
        }
        boolean retryable = status == 429 || status >= 500;
        throw new KeycloakException(status, "Keycloak ответил " + status + " на " + method + " " + describe(path), retryable);
    }

    private HttpResponse<String> exchange(String method, String path, String body, Instant deadline) {
        String bearer = accessToken(deadline);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/admin/realms/" + realm + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .method(method, publisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return send(builder, deadline, method + " " + describe(path));
    }

    private synchronized String accessToken(Instant deadline) {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiresAt)) {
            return token;
        }
        String form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret.get());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/realms/" + realm + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        HttpResponse<String> response = send(builder, deadline, "получение токена");
        if (response.statusCode() != 200) {
            throw new KeycloakException(response.statusCode(), "Keycloak не выдал токен клиента " + clientId + ": ответ " + response.statusCode(),
                    response.statusCode() == 429 || response.statusCode() >= 500);
        }
        Object parsed = Json.parse(response.body());
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("access_token") instanceof String access) || access.isBlank()) {
            throw new KeycloakException(200, "Keycloak вернул токен в неожиданном виде", false);
        }
        long seconds = map.get("expires_in") instanceof Number n ? n.longValue() : 60;
        token = access;
        tokenExpiresAt = clock.instant().plusSeconds(seconds).minus(TOKEN_SKEW);
        return token;
    }

    private synchronized void invalidateToken() {
        token = null;
        tokenExpiresAt = Instant.MIN;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, Instant deadline, String what) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new KeycloakException(0, "Лимит времени вызова Keycloak исчерпан: " + what, true);
        }
        try {
            return http.send(builder.timeout(remaining).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new KeycloakException(0, "Keycloak не ответил за отведённое время: " + what, true, e);
        } catch (IOException e) {
            LOG.warn("Keycloak недоступен ({}): {}", what, e.getClass().getSimpleName());
            throw new KeycloakException(0, "Keycloak недоступен: " + what, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeycloakException(0, "Вызов Keycloak прерван: " + what, true, e);
        }
    }

    /** Путь без идентификаторов для сообщений: идентификаторы пользователей в тексты ошибок не идут. */
    private static String describe(String path) {
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        StringBuilder out = new StringBuilder();
        for (String part : clean.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            out.append('/').append(part.length() >= 32 ? "{id}" : part);
        }
        return out.toString();
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не задан идентификатор для обращения к Keycloak");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
