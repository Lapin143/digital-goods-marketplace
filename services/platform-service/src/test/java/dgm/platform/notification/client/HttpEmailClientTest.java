package dgm.platform.notification.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import dgm.platform.notification.service.EmailSendException;
import dgm.platform.notification.service.OutgoingEmail;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Клиент провайдера почты против HTTP-сервера в процессе: запрос, ключ, разбор кодов ответа на отказ и временный сбой, недоступность.
 * Без TLS: контекст нужен только для https.
 */
class HttpEmailClientTest {

    private static final OutgoingEmail MAIL = new OutgoingEmail("0199e0a0-0000-4000-8000-0000000000a1", "anna@mail.example",
            "Заявка продавца отклонена", "Здравствуйте, Анна! Причина: нет скана.");

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
            byte[] answer = "{\"messageId\":\"m-1\",\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpEmailClient client(String base) throws Exception {
        return new HttpEmailClient(base, () -> SecretValue.of("secret-email-key"), SSLContext.getDefault());
    }

    @Test
    void sendsTheLetterToThePathWithBearerKeyAndMessageId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        client(start(202, seen)).send(MAIL);

        String[] lines = seen.get().split("\n", 4);
        assertEquals("POST /email/v1/messages", lines[0]);
        assertEquals("Bearer secret-email-key", lines[1]);
        assertTrue(lines[2].startsWith("application/json"));
        assertEquals(Map.of("messageId", "0199e0a0-0000-4000-8000-0000000000a1", "to", "anna@mail.example", "subject", "Заявка продавца отклонена",
                "text", "Здравствуйте, Анна! Причина: нет скана."), Json.parseObject(lines[3]));
    }

    @Test
    void trailingSlashInTheBaseAddressIsHarmless() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        client(start(202, seen) + "/").send(MAIL);

        assertTrue(seen.get().startsWith("POST /email/v1/messages\n"));
    }

    @Test
    void anyTwoHundredCodeMeansAccepted() throws Exception {
        client(start(200, new AtomicReference<>())).send(MAIL);
    }

    @Test
    void badRequestAndUnprocessableAreRefusalsThatRetryCannotFix() throws Exception {
        for (int status : new int[] {400, 422}) {
            HttpEmailClient client = client(start(status, new AtomicReference<>()));

            EmailSendException e = assertThrows(EmailSendException.class, () -> client.send(MAIL));

            assertTrue(e.permanent(), "код " + status);
            assertEquals("address_rejected", e.code());
            server.stop(0);
        }
    }

    @Test
    void otherErrorsAreTemporaryWithTheirOwnCodes() throws Exception {
        Map<Integer, String> expected = Map.of(429, "provider_rate_limited", 401, "provider_auth", 403, "provider_auth", 500, "provider_unavailable",
                503, "provider_unavailable", 404, "provider_http_404");
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            HttpEmailClient client = client(start(entry.getKey(), new AtomicReference<>()));

            EmailSendException e = assertThrows(EmailSendException.class, () -> client.send(MAIL));

            assertFalse(e.permanent(), "код " + entry.getKey());
            assertEquals(entry.getValue(), e.code(), "код " + entry.getKey());
            server.stop(0);
        }
    }

    @Test
    void errorMessagesNeverContainAddressTextOrKey() throws Exception {
        for (int status : new int[] {400, 401, 503}) {
            HttpEmailClient client = client(start(status, new AtomicReference<>()));

            EmailSendException e = assertThrows(EmailSendException.class, () -> client.send(MAIL));

            assertTrue(e.getMessage().contains(Integer.toString(status)));
            assertFalse(e.getMessage().contains("anna@"));
            assertFalse(e.getMessage().contains("скана"));
            assertFalse(e.getMessage().contains("secret-email-key"));
            server.stop(0);
        }
    }

    @Test
    void unreachableProviderIsATemporaryFailure() throws Exception {
        String base = start(202, new AtomicReference<>());
        server.stop(0);

        EmailSendException e = assertThrows(EmailSendException.class, () -> client(base).send(MAIL));

        assertFalse(e.permanent());
        assertEquals("provider_unavailable", e.code());
        assertFalse(e.getMessage().contains("anna@"));
        assertFalse(e.getMessage().contains("secret-email-key"));
    }

    @Test
    void providerThatDoesNotAnswerInTimeIsATemporaryTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(HttpEmailClient.CALL_LIMIT.toMillis() + 1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        HttpEmailClient client = client("http://127.0.0.1:" + server.getAddress().getPort());

        EmailSendException e = assertThrows(EmailSendException.class, () -> client.send(MAIL));

        assertFalse(e.permanent());
        assertEquals("provider_timeout", e.code());
    }

    @Test
    void errorCodeMustFitTheColumn() {
        assertThrows(IllegalArgumentException.class, () -> new EmailSendException("Bad Code", false, "x"));
        assertThrows(IllegalArgumentException.class, () -> new EmailSendException("a".repeat(65), false, "x"));
        assertThrows(IllegalArgumentException.class, () -> new EmailSendException(null, false, "x"));
    }
}
