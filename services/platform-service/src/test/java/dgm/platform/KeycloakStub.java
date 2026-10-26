package dgm.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dgm.kit.json.Json;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin REST Keycloak в памяти для интеграционного теста: ровно те вызовы, что делает {@code KeycloakAdminClient}. Настоящий Keycloak
 * в задании {@code kit} не поднимается (его проверяет задание {@code e2e}); заглушка нужна, чтобы видеть, что именно платформа попросила.
 */
final class KeycloakStub implements AutoCloseable {

    /** Пользователь в заглушке. */
    static final class User {
        final String id;
        volatile String email;
        final String firstName;
        final String lastName;
        volatile boolean enabled = true;
        volatile boolean otp;
        volatile boolean federated;
        final Set<String> roles = Collections.synchronizedSet(new LinkedHashSet<>());
        final Set<String> requiredActions = Collections.synchronizedSet(new LinkedHashSet<>());
        final Map<String, List<String>> attributes = new ConcurrentHashMap<>();
        volatile boolean loggedOut;

        User(String id, String email, String firstName, String lastName) {
            this.id = id;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
        }
    }

    private static final Pattern USER = Pattern.compile("^/auth/admin/realms/dgm/users/([^/]+)(/.*)?$");
    private static final Pattern ROLE = Pattern.compile("^/auth/admin/realms/dgm/roles/([^/]+)$");

    private final HttpServer server;
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean down;
    private volatile boolean refuseRoleMappings;

    KeycloakStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/auth";
    }

    User add(String role, String firstName, String lastName) {
        String id = UUID.randomUUID().toString();
        User user = new User(id, "u" + id.substring(0, 8) + "@mail.example", firstName, lastName);
        user.roles.add(role.equals("buyer") ? "default-roles-dgm" : role);
        users.put(id, user);
        return user;
    }

    User get(String id) {
        return users.get(id);
    }

    /** Все вызовы Admin REST, по одному на строку «метод путь». */
    List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** Keycloak недоступен: все вызовы получают 503. */
    void down(boolean value) {
        this.down = value;
    }

    /** Назначение и снятие ролей получает 503, остальное работает. */
    void refuseRoleMappings(boolean value) {
        this.refuseRoleMappings = value;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.endsWith("/protocol/openid-connect/token")) {
            reply(exchange, 200, "{\"access_token\":\"stub-token\",\"expires_in\":300}");
            return;
        }
        requests.add(method + " " + path);
        if (down) {
            reply(exchange, 503, "{}");
            return;
        }
        Matcher user = USER.matcher(path);
        Matcher role = ROLE.matcher(path);
        if (method.equals("GET") && path.equals("/auth/admin/realms/dgm/users")) {
            listUsers(exchange);
        } else if (role.matches() && method.equals("GET")) {
            // как настоящий Keycloak для служебной записи с manage-users, view-users и query-users: чтение роли по имени требует view-realm
            reply(exchange, 403, "{\"error\":\"HTTP 403 Forbidden\"}");
        } else if (user.matches()) {
            userCall(exchange, method, user.group(1), user.group(2) == null ? "" : user.group(2), body);
        } else {
            reply(exchange, 404, "{}");
        }
    }

    private void listUsers(HttpExchange exchange) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                query.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        int first = Integer.parseInt(query.getOrDefault("first", "0"));
        int max = Integer.parseInt(query.getOrDefault("max", "100"));
        List<Object> page = new ArrayList<>();
        users.values().stream().sorted((a, b) -> a.id.compareTo(b.id)).skip(first).limit(max).forEach(u -> page.add(representation(u)));
        reply(exchange, 200, Json.write(page));
    }

    private void userCall(HttpExchange exchange, String method, String id, String rest, String body) throws IOException {
        User user = users.get(id);
        if (user == null) {
            reply(exchange, 404, "{}");
            return;
        }
        switch (rest + " " + method) {
            case " GET" -> reply(exchange, 200, Json.write(representation(user)));
            case " PUT" -> {
                Map<String, Object> rep = Json.parseObject(body);
                // Как настоящий Keycloak при duplicateEmailsAllowed = false: адрес другого пользователя занят, 409
                if (rep.get("email") instanceof String email && !email.equalsIgnoreCase(user.email)) {
                    boolean taken = users.values().stream().anyMatch(other -> other != user && email.equalsIgnoreCase(other.email));
                    if (taken) {
                        reply(exchange, 409, "{\"errorMessage\":\"User exists with same email\"}");
                        return;
                    }
                    user.email = email;
                }
                user.enabled = !Boolean.FALSE.equals(rep.get("enabled"));
                user.requiredActions.clear();
                if (rep.get("requiredActions") instanceof List<?> actions) {
                    actions.forEach(a -> user.requiredActions.add(String.valueOf(a)));
                }
                user.attributes.clear();
                if (rep.get("attributes") instanceof Map<?, ?> attributes) {
                    attributes.forEach((k, v) -> user.attributes.put(String.valueOf(k), List.of(String.valueOf(((List<?>) v).get(0)))));
                }
                reply(exchange, 204, null);
            }
            case " DELETE" -> {
                users.remove(id);
                reply(exchange, 204, null);
            }
            case "/logout POST" -> {
                user.loggedOut = true;
                reply(exchange, 204, null);
            }
            case "/credentials GET" -> {
                List<Object> credentials = new ArrayList<>();
                credentials.add(Map.of("id", "c-password", "type", "password"));
                if (user.otp) {
                    credentials.add(Map.of("id", "c-otp", "type", "otp"));
                }
                reply(exchange, 200, Json.write(credentials));
            }
            case "/federated-identity GET" -> reply(exchange, 200,
                    Json.write(user.federated ? List.of(Map.of("identityProvider", "vk-id", "userId", "1")) : List.of()));
            case "/role-mappings/realm GET" -> {
                List<Object> mappings = new ArrayList<>();
                synchronized (user.roles) {
                    user.roles.forEach(r -> mappings.add(Map.of("id", "role-" + r, "name", r)));
                }
                reply(exchange, 200, Json.write(mappings));
            }
            case "/role-mappings/realm/available GET" -> {
                List<Object> available = new ArrayList<>();
                Set<String> effective = effectiveRoles(user);
                for (String name : REALM_ROLES) {
                    if (!effective.contains(name)) {
                        available.add(Map.of("id", "role-" + name, "name", name));
                    }
                }
                reply(exchange, 200, Json.write(available));
            }
            case "/role-mappings/realm/composite GET" -> {
                List<Object> effective = new ArrayList<>();
                effectiveRoles(user).forEach(r -> effective.add(Map.of("id", "role-" + r, "name", r)));
                reply(exchange, 200, Json.write(effective));
            }
            case "/role-mappings/realm POST", "/role-mappings/realm DELETE" -> {
                if (refuseRoleMappings) {
                    reply(exchange, 503, "{}");
                    return;
                }
                if (Json.parse(body) instanceof List<?> list) {
                    for (Object item : list) {
                        String name = String.valueOf(((Map<?, ?>) item).get("name"));
                        if (method.equals("POST")) {
                            user.roles.add(name);
                        } else {
                            user.roles.remove(name);
                        }
                    }
                }
                reply(exchange, 204, null);
            }
            default -> reply(exchange, 404, "{}");
        }
    }

    /** Роли realm: пять ролей приложения и роль по умолчанию, в которую входит «покупатель». */
    private static final List<String> REALM_ROLES = List.of("buyer", "seller", "moderator", "support-operator", "admin", "default-roles-dgm");

    private static Set<String> effectiveRoles(User user) {
        Set<String> effective = new LinkedHashSet<>();
        synchronized (user.roles) {
            effective.addAll(user.roles);
        }
        if (effective.contains("default-roles-dgm")) {
            effective.add("buyer");
        }
        return effective;
    }

    private static Map<String, Object> representation(User user) {
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("id", user.id);
        rep.put("username", user.email);
        rep.put("email", user.email);
        rep.put("firstName", user.firstName);
        rep.put("lastName", user.lastName);
        rep.put("enabled", user.enabled);
        rep.put("emailVerified", true);
        rep.put("createdTimestamp", 1_760_000_000_000L);
        rep.put("attributes", new LinkedHashMap<>(user.attributes));
        synchronized (user.requiredActions) {
            rep.put("requiredActions", new ArrayList<>(user.requiredActions));
        }
        return rep;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
