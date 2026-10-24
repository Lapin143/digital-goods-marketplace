package dgm.delivery.delivery.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dgm.delivery.delivery.domain.AttemptResult;
import dgm.delivery.delivery.domain.ProviderLookup;
import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.letter.Letter;
import dgm.kit.json.Json;
import dgm.kit.secret.SecretValue;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Клиент провайдера почты (TC-603, TC-604, ADR-011): запрос с ключом провайдера и идентификатором сообщения, равным идентификатору выдачи;
 * перевод ответа в исход попытки: 2xx принято, 400 и 422 окончательная ошибка, остальное и недоступность временная. Без TLS: контекст нужен
 * только для https. Запрос статуса письма (TC-607): 200 с телом {@code {messageId, status, reason?}} это известный статус, 404 «письма не знаю»,
 * остальное «не ответил».
 */
@Tag("TC-603")
@Tag("TC-604")
@Tag("TC-607")
class HttpEmailClientTest {

    private static final UUID MESSAGE = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final Letter LETTER = new Letter("Ваши ключи: Игра", "1. AAAAA-11111\n2. BBBBB-22222\n");

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status, AtomicReference<String> seen, long delayMillis) throws IOException {
        return start(status, "{\"messageId\":\"m-1\",\"status\":\"accepted\"}", seen, delayMillis);
    }

    private String start(int status, String answerBody, AtomicReference<String> seen, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + "\n" + exchange.getRequestHeaders().getFirst("Authorization")
                    + "\n" + exchange.getRequestHeaders().getFirst("Content-Type") + "\n" + body);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] answer = answerBody.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, answer.length);
                exchange.getResponseBody().write(answer);
            } catch (IOException e) {
                // клиент ушёл по тайм-ауту
            } finally {
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpEmailClient client(String base) throws Exception {
        return new HttpEmailClient(base, () -> SecretValue.of("secret-email-key"), SSLContext.getDefault());
    }

    @Test
    void theRequestCarriesTheProviderKeyTheMessageIdOfTheDeliveryTheAddressAndTheLetter() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String base = start(202, seen, 0);

        AttemptResult result = client(base).send(MESSAGE, "anna@mail.example", LETTER);

        assertEquals(new AttemptResult.Accepted(), result);
        String[] parts = seen.get().split("\n", 4);
        assertEquals("POST /email/v1/messages", parts[0]);
        assertEquals("Bearer secret-email-key", parts[1]);
        assertEquals("application/json", parts[2]);
        Map<String, Object> body = Json.parseObject(parts[3]);
        assertEquals(MESSAGE.toString(), body.get("messageId"));
        assertEquals(List.of("anna@mail.example"), body.get("to"));
        assertEquals("Ваши ключи: Игра", body.get("subject"));
        assertEquals("1. AAAAA-11111\n2. BBBBB-22222\n", body.get("text"));
    }

    @Test
    void anyTwoHundredAnswerMeansTheLetterIsAccepted() throws Exception {
        String base = start(200, new AtomicReference<>(), 0);

        assertEquals(new AttemptResult.Accepted(), client(base).send(MESSAGE, "anna@mail.example", LETTER));
    }

    @Test
    void aRefusalByTheAddressOrTheLetterIsPermanent() throws Exception {
        for (int status : new int[] {400, 422}) {
            String base = start(status, new AtomicReference<>(), 0);

            assertEquals(new AttemptResult.Permanent("address_rejected"), client(base).send(MESSAGE, "anna@mail.example", LETTER), "статус " + status);

            server.stop(0);
        }
    }

    @Test
    void aBusyOrBrokenProviderIsATemporaryErrorWithItsOwnCode() throws Exception {
        String[][] cases = {{"429", "provider_rate_limited"}, {"500", "provider_unavailable"}, {"503", "provider_unavailable"}, {"401", "provider_auth"},
            {"403", "provider_auth"}, {"404", "provider_http_404"}};
        for (String[] one : cases) {
            String base = start(Integer.parseInt(one[0]), new AtomicReference<>(), 0);

            assertEquals(new AttemptResult.Retryable(one[1]), client(base).send(MESSAGE, "anna@mail.example", LETTER), "статус " + one[0]);

            server.stop(0);
        }
    }

    @Test
    void anUnreachableProviderIsATemporaryError() throws Exception {
        String base = start(202, new AtomicReference<>(), 0);
        server.stop(0);

        assertEquals(new AttemptResult.Retryable("provider_unavailable"), client(base).send(MESSAGE, "anna@mail.example", LETTER));
    }

    @Test
    void aSilentProviderIsATemporaryErrorByTimeoutAfterFiveSeconds() throws Exception {
        String base = start(202, new AtomicReference<>(), 6500);
        long begin = System.nanoTime();

        AttemptResult result = client(base).send(MESSAGE, "anna@mail.example", LETTER);

        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        assertEquals(new AttemptResult.Retryable("provider_timeout"), result);
        assertTrue(elapsedMillis >= 4_900 && elapsedMillis < 6_400, "предел вызова 5 секунд: " + elapsedMillis + " мс");
    }

    @Test
    void theStatusRequestIsAGetWithTheProviderKeyAndTheMessageIdInThePath() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String base = start(200, "{\"messageId\":\"" + MESSAGE + "\",\"status\":\"delivered\"}", seen, 0);

        ProviderLookup result = client(base).statusOf(MESSAGE);

        assertEquals(new ProviderLookup.Known(ProviderStatus.DELIVERED, null), result);
        String[] parts = seen.get().split("\n", 4);
        assertEquals("GET /email/v1/messages/" + MESSAGE, parts[0]);
        assertEquals("Bearer secret-email-key", parts[1]);
    }

    @Test
    void theStatusAnswerIsTranslatedWithTheReasonOfAFailure() throws Exception {
        String[][] cases = {{"accepted", "ACCEPTED", null}, {"delivered", "DELIVERED", null}, {"failed", "FAILED", "address_rejected"}, {"bounced", "OTHER", null}};
        for (String[] one : cases) {
            String answer = "{\"messageId\":\"m-1\",\"status\":\"" + one[0] + "\"" + (one[2] == null ? "" : ",\"reason\":\"" + one[2] + "\"") + "}";
            String base = start(200, answer, new AtomicReference<>(), 0);

            assertEquals(new ProviderLookup.Known(ProviderStatus.valueOf(one[1]), one[2]), client(base).statusOf(MESSAGE), "статус " + one[0]);

            server.stop(0);
        }
    }

    @Test
    void aMessageTheProviderDoesNotKnowIsUnknownAndOtherFailuresAreSilence() throws Exception {
        String base = start(404, "{}", new AtomicReference<>(), 0);
        assertEquals(new ProviderLookup.Unknown(), client(base).statusOf(MESSAGE));
        server.stop(0);

        String[][] cases = {{"429", "provider_rate_limited"}, {"500", "provider_unavailable"}, {"401", "provider_auth"}, {"418", "provider_http_418"}};
        for (String[] one : cases) {
            base = start(Integer.parseInt(one[0]), "{}", new AtomicReference<>(), 0);

            assertEquals(new ProviderLookup.Unavailable(one[1]), client(base).statusOf(MESSAGE), "статус " + one[0]);

            server.stop(0);
        }
    }

    @Test
    void aBrokenStatusBodyAndAnUnreachableProviderAreSilence() throws Exception {
        for (String broken : new String[] {"не JSON", "[1]", "{}", "{\"status\": 5}"}) {
            String base = start(200, broken, new AtomicReference<>(), 0);

            assertEquals(new ProviderLookup.Unavailable("provider_bad_response"), client(base).statusOf(MESSAGE), broken);

            server.stop(0);
        }
        String base = start(200, "{}", new AtomicReference<>(), 0);
        server.stop(0);

        assertEquals(new ProviderLookup.Unavailable("provider_unavailable"), client(base).statusOf(MESSAGE));
    }
}
