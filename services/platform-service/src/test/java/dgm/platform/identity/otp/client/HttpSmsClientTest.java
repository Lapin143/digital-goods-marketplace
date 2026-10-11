package dgm.platform.identity.otp.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.otp.service.SmsSendException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Клиент провайдера SMS против HTTP-сервера в процессе: запрос, ключ, коды ответа, недоступность. Без TLS: контекст нужен только для https. */
class HttpSmsClientTest {

    private static final PhoneNumber PHONE = new PhoneNumber("+79001234567");

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status, AtomicReference<String> seen) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + "\n"
                    + exchange.getRequestHeaders().getFirst("Authorization") + "\n" + exchange.getRequestHeaders().getFirst("Content-Type")
                    + "\n" + body);
            byte[] answer = "{\"messageId\":\"sms-1\",\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpSmsClient client(String base) throws Exception {
        return new HttpSmsClient(base, () -> SecretValue.of("secret-sms-key"), SSLContext.getDefault());
    }

    @Test
    void sendsTheMessageToThePathWithBearerKeyAndJsonBody() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        client(start(202, seen)).send(PHONE, "Код 123456");

        String[] lines = seen.get().split("\n", 4);
        assertEquals("POST /sms/v1/messages", lines[0]);
        assertEquals("Bearer secret-sms-key", lines[1]);
        assertTrue(lines[2].startsWith("application/json"));
        assertEquals(Map.of("to", "+79001234567", "text", "Код 123456"), Json.parseObject(lines[3]));
    }

    @Test
    void trailingSlashInTheBaseAddressIsHarmless() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        client(start(202, seen) + "/").send(PHONE, "Код 123456");

        assertTrue(seen.get().startsWith("POST /sms/v1/messages\n"));
    }

    @Test
    void providerErrorBecomesSendExceptionWithoutPhoneTextOrKey() throws Exception {
        for (int status : new int[] {401, 422, 500, 503}) {
            AtomicReference<String> seen = new AtomicReference<>();
            HttpSmsClient client = client(start(status, seen));

            SmsSendException e = assertThrows(SmsSendException.class, () -> client.send(PHONE, "Код 123456"));

            assertTrue(e.getMessage().contains(Integer.toString(status)));
            assertFalse(e.getMessage().contains("7900"));
            assertFalse(e.getMessage().contains("123456"));
            assertFalse(e.getMessage().contains("secret-sms-key"));
            server.stop(0);
        }
    }

    @Test
    void unreachableProviderBecomesSendException() throws Exception {
        String base = start(202, new AtomicReference<>());
        server.stop(0);

        SmsSendException e = assertThrows(SmsSendException.class, () -> client(base).send(PHONE, "Код 123456"));

        assertFalse(e.getMessage().contains("7900"));
        assertFalse(e.getMessage().contains("secret-sms-key"));
    }
}
