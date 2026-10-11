package dgm.kit.keycloak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.json.Json;
import dgm.kit.time.ManualClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Клиент Admin REST на локальном HTTP-сервере JDK: токен, постраничное чтение, идемпотентные изменения, лимит времени, тексты ошибок. */
class KeycloakAdminClientTest {

    private static final String USER_ID = "0199e0a0-0000-4000-8000-000000000001";
    private static final String SECRET = "very-secret-client-value";

    private record Req(String method, String path, String query, String body, String authorization) {
        String line() {
            return method + " " + path + (query == null ? "" : "?" + query);
        }
    }

    private record Resp(int status, String body) {
    }

    private HttpServer server;
    private final List<Req> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Req, Resp> router;
    private ManualClock clock;
    private int tokens;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Req req = new Req(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(), body,
                    exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(req);
            Resp resp = router.apply(req);
            if (resp.body() == null) {
                exchange.sendResponseHeaders(resp.status(), -1);
            } else {
                byte[] bytes = resp.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(resp.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        clock = new ManualClock(Instant.parse("2026-10-11T10:00:00Z"));
        tokens = 0;
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private KeycloakAdminClient client(Duration limit) throws NoSuchAlgorithmException {
        String base = "http://localhost:" + server.getAddress().getPort() + "/auth";
        return new KeycloakAdminClient(base, "dgm", "platform-service", () -> SECRET, SSLContext.getDefault(), clock, limit);
    }

    private KeycloakAdminClient client() throws NoSuchAlgorithmException {
        return client(Duration.ofSeconds(2));
    }

    /** Ответ на выдачу токена: номер токена растёт при каждой выдаче. */
    private Resp token(Req req, int expiresIn) {
        tokens++;
        return new Resp(200, "{\"access_token\":\"tok-" + tokens + "\",\"expires_in\":" + expiresIn + "}");
    }

    private long count(String line) {
        return requests.stream().filter(r -> r.line().equals(line)).count();
    }

    private static final String TOKEN_LINE = "POST /auth/realms/dgm/protocol/openid-connect/token";

    @Test
    void tokenIsRequestedOnceAndReused() throws Exception {
        router = req -> req.path().endsWith("/token") ? token(req, 300) : new Resp(200, "5");
        KeycloakAdminClient client = client();
        assertEquals(5, client.countUsers());
        assertEquals(5, client.countUsers());
        assertEquals(1, count(TOKEN_LINE));
        Req admin = requests.stream().filter(r -> r.path().endsWith("/users/count")).findFirst().orElseThrow();
        assertEquals("Bearer tok-1", admin.authorization());
        Req tokenRequest = requests.get(0);
        assertTrue(tokenRequest.body().contains("grant_type=client_credentials"));
        assertTrue(tokenRequest.body().contains("client_id=platform-service"));
        assertTrue(tokenRequest.body().contains("client_secret=" + SECRET));
    }

    @Test
    void tokenIsRefreshedShortlyBeforeItExpires() throws Exception {
        router = req -> req.path().endsWith("/token") ? token(req, 60) : new Resp(200, "1");
        KeycloakAdminClient client = client();
        client.countUsers();
        clock.advance(Duration.ofSeconds(29));
        client.countUsers();
        assertEquals(1, count(TOKEN_LINE));
        // 60 с жизни минус запас 30 с: после 30 с токен считается истёкшим
        clock.advance(Duration.ofSeconds(2));
        client.countUsers();
        assertEquals(2, count(TOKEN_LINE));
    }

    @Test
    void rejectedTokenIsRefreshedOnceAndTheCallRepeated() throws Exception {
        router = req -> {
            if (req.path().endsWith("/token")) {
                return token(req, 300);
            }
            return "Bearer tok-1".equals(req.authorization()) ? new Resp(401, "{}") : new Resp(200, "7");
        };
        assertEquals(7, client().countUsers());
        assertEquals(2, count(TOKEN_LINE));
    }

    @Test
    void usersAreReadPageByPage() throws Exception {
        router = req -> {
            if (req.path().endsWith("/token")) {
                return token(req, 300);
            }
            return new Resp(200, "[{\"id\":\"" + USER_ID + "\",\"username\":\"ivan\",\"email\":\"ivan@example.org\",\"enabled\":true,"
                    + "\"emailVerified\":true,\"createdTimestamp\":1760000000000,\"attributes\":{\"seller_id\":[\"s-1\"],\"phone\":[\"+79990000000\"]}}]");
        };
        List<KeycloakUser> page = client().listUsers(100, 50);
        assertEquals(1, page.size());
        KeycloakUser user = page.get(0);
        assertEquals(USER_ID, user.id());
        assertEquals("ivan", user.username());
        assertTrue(user.enabled());
        assertEquals(1760000000000L, user.createdTimestamp());
        assertEquals("s-1", user.attribute("seller_id"));
        assertEquals(null, user.attribute("missing"));
        assertEquals("GET /auth/admin/realms/dgm/users?first=100&max=50&briefRepresentation=false", requests.get(1).line());
        assertEquals("KeycloakUser[" + USER_ID + "]", user.toString());
        assertFalse(user.toString().contains("example.org"));
    }

    @Test
    void pageParametersAreValidated() throws Exception {
        KeycloakAdminClient client = client();
        assertThrows(IllegalArgumentException.class, () -> client.listUsers(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> client.listUsers(0, 0));
        assertThrows(IllegalArgumentException.class, () -> client.listUsers(0, 1001));
        assertTrue(requests.isEmpty());
    }

    @Test
    void missingUserIsEmpty() throws Exception {
        router = req -> req.path().endsWith("/token") ? token(req, 300) : new Resp(404, "{\"error\":\"User not found\"}");
        assertTrue(client().findUser(USER_ID).isEmpty());
    }

    @Test
    void attributesAreMergedIntoFullRepresentationAndUnchangedValuesSkipTheUpdate() throws Exception {
        router = req -> {
            if (req.path().endsWith("/token")) {
                return token(req, 300);
            }
            if (req.method().equals("GET")) {
                return new Resp(200, "{\"id\":\"" + USER_ID + "\",\"username\":\"ivan\",\"enabled\":true,\"attributes\":{\"seller_id\":[\"a\"]}}");
            }
            return new Resp(204, null);
        };
        KeycloakAdminClient client = client();

        client.setAttributes(USER_ID, Map.of("seller_id", "a"));
        assertEquals(0, requests.stream().filter(r -> r.method().equals("PUT")).count());

        client.setAttributes(USER_ID, Map.of("seller_id", "b", "phone_verified", "true"));
        Req put = requests.stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        Map<String, Object> sent = Json.parseObject(put.body());
        assertEquals("ivan", sent.get("username"));
        assertEquals(Boolean.TRUE, sent.get("enabled"));
        Map<?, ?> attributes = (Map<?, ?>) sent.get("attributes");
        assertEquals(List.of("b"), attributes.get("seller_id"));
        assertEquals(List.of("true"), attributes.get("phone_verified"));
    }

    @Test
    void attributeCanBeRemoved() throws Exception {
        router = req -> {
            if (req.path().endsWith("/token")) {
                return token(req, 300);
            }
            if (req.method().equals("GET")) {
                return new Resp(200, "{\"id\":\"" + USER_ID + "\",\"attributes\":{\"seller_id\":[\"a\"],\"other\":[\"x\"]}}");
            }
            return new Resp(204, null);
        };
        client().setAttributes(USER_ID, new java.util.HashMap<>(Map.of("seller_id", "")) {
            {
                put("seller_id", null);
            }
        });
        Req put = requests.stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        Map<?, ?> attributes = (Map<?, ?>) Json.parseObject(put.body()).get("attributes");
        assertFalse(attributes.containsKey("seller_id"));
        assertEquals(List.of("x"), attributes.get("other"));
    }

    @Test
    void disablingAnAlreadyDisabledUserDoesNotCallUpdate() throws Exception {
        router = req -> {
            if (req.path().endsWith("/token")) {
                return token(req, 300);
            }
            return req.method().equals("GET") ? new Resp(200, "{\"id\":\"" + USER_ID + "\",\"enabled\":false}") : new Resp(204, null);
        };
        KeycloakAdminClient client = client();
        client.setEnabled(USER_ID, false);
        assertEquals(0, requests.stream().filter(r -> r.method().equals("PUT")).count());
        client.setEnabled(USER_ID, true);
        Req put = requests.stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, Json.parseObject(put.body()).get("enabled"));
    }

    @Test
    void realmRolesAreAddedAndRemovedByRepresentation() throws Exception {
        router = req -> {
            if (req.path().endsWith("/token")) {
                return token(req, 300);
            }
            if (req.path().endsWith("/roles/seller")) {
                return new Resp(200, "{\"id\":\"role-1\",\"name\":\"seller\",\"composite\":false}");
            }
            if (req.path().endsWith("/role-mappings/realm") && req.method().equals("GET")) {
                return new Resp(200, "[{\"id\":\"role-0\",\"name\":\"buyer\"}]");
            }
            return new Resp(204, null);
        };
        KeycloakAdminClient client = client();
        assertEquals(List.of("buyer"), client.realmRoles(USER_ID));
        client.addRealmRole(USER_ID, "seller");
        client.removeRealmRole(USER_ID, "seller");
        List<Req> mappings = requests.stream().filter(r -> r.path().endsWith("/role-mappings/realm") && !r.method().equals("GET")).toList();
        assertEquals(List.of("POST", "DELETE"), mappings.stream().map(Req::method).toList());
        for (Req mapping : mappings) {
            assertEquals(List.of(Map.of("id", "role-1", "name", "seller")), Json.parse(mapping.body()));
        }
    }

    @Test
    void credentialsAreListedAndDeletedWithoutFailingOnMissing() throws Exception {
        router = req -> {
            if (req.path().endsWith("/token")) {
                return token(req, 300);
            }
            if (req.method().equals("GET")) {
                return new Resp(200, "[{\"id\":\"c1\",\"type\":\"password\"},{\"id\":\"c2\",\"type\":\"otp\"}]");
            }
            return new Resp(404, "{}");
        };
        KeycloakAdminClient client = client();
        assertEquals(List.of("password", "otp"), client.credentialTypes(USER_ID));
        assertTrue(client.hasCredential(USER_ID, "otp"));
        assertFalse(client.hasCredential(USER_ID, "webauthn"));
        client.deleteCredential(USER_ID, "c2");
    }

    @Test
    void slowKeycloakFailsFastAndTheErrorIsRetryable() throws Exception {
        router = req -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Resp(200, "{\"access_token\":\"t\",\"expires_in\":300}");
        };
        KeycloakAdminClient client = client(Duration.ofMillis(250));
        long started = System.nanoTime();
        KeycloakException e = assertThrows(KeycloakException.class, client::countUsers);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(0, e.status());
        assertTrue(e.retryable());
        assertTrue(elapsedMs < 700, "ожидание дольше лимита: " + elapsedMs + " мс");
    }

    @Test
    void serverErrorsAreRetryableAndClientErrorsAreNot() throws Exception {
        router = req -> req.path().endsWith("/token") ? token(req, 300) : new Resp(503, "{}");
        KeycloakException unavailable = assertThrows(KeycloakException.class, () -> client().countUsers());
        assertEquals(503, unavailable.status());
        assertTrue(unavailable.retryable());

        router = req -> req.path().endsWith("/token") ? token(req, 300) : new Resp(403, "{}");
        KeycloakException forbidden = assertThrows(KeycloakException.class, () -> client().countUsers());
        assertEquals(403, forbidden.status());
        assertFalse(forbidden.retryable());
    }

    @Test
    void tokenFailureNamesTheClientButNeverTheSecret() throws Exception {
        router = req -> new Resp(401, "{\"error\":\"unauthorized_client\",\"client_secret\":\"" + SECRET + "\"}");
        KeycloakException e = assertThrows(KeycloakException.class, () -> client().countUsers());
        assertEquals(401, e.status());
        assertTrue(e.getMessage().contains("platform-service"));
        assertFalse(e.getMessage().contains(SECRET));
        assertFalse(e.getMessage().contains("unauthorized_client"));
    }

    @Test
    void errorMessagesDoNotContainUserIdentifiers() throws Exception {
        router = req -> req.path().endsWith("/token") ? token(req, 300) : new Resp(500, "{}");
        KeycloakException e = assertThrows(KeycloakException.class, () -> client().setEnabled(USER_ID, false));
        assertFalse(e.getMessage().contains(USER_ID), e.getMessage());
        assertTrue(e.getMessage().contains("{id}"), e.getMessage());
    }

    @Test
    void blankIdentifierIsRejectedBeforeAnyCall() throws Exception {
        KeycloakAdminClient client = client();
        assertThrows(IllegalArgumentException.class, () -> client.findUser(" "));
        assertTrue(requests.isEmpty());
    }
}
