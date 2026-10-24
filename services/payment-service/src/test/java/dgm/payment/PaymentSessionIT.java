package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Открытие платёжной сессии на стенде (SEQ-01 шаги 12 и 13, ADR-015, INV-11, ST-02): настоящая база, настоящая заглушка шлюза,
 * настоящие сертификаты вызывающих. Сессия открывается, повтор отдаёт ту же, отказ шлюза даёт 503 и оставляет строку без внешнего
 * идентификатора, платёж в другом статусе даёт 409, чужой вызывающий получает 403.
 */
@Tag("TC-501")
class PaymentSessionIT extends PaymentStandIT {

    private static final long AMOUNT = 299800;

    @Test
    void theSessionIsOpenedAndTheGatewayHoldsExactlyOnePayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        Response response = open(orderId, AMOUNT);

        assertEquals(201, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        Map<String, Object> view = response.json();
        assertEquals(Set.of("paymentId", "orderId", "status", "sessionUrl", "sessionExpiresAt"), view.keySet());
        assertEquals(orderId.toString(), view.get("orderId"));
        assertEquals("created", view.get("status"));

        Map<String, Object> row = payment(orderId);
        assertNotNull(row);
        assertEquals(view.get("paymentId"), row.get("id").toString());
        assertEquals("created", row.get("status"));
        assertEquals(AMOUNT, ((Number) row.get("amount")).longValue());
        assertNotNull(row.get("gateway_payment_id"));
        assertEquals(view.get("sessionUrl"), row.get("session_url"));

        var gateway = gatewayPaymentsOf(orderId);
        assertEquals(1, gateway.size());
        assertEquals(row.get("gateway_payment_id"), gateway.get(0).get("paymentId"));
        assertEquals(Map.of("amount", (int) AMOUNT, "currency", "RUB"), gateway.get(0).get("amount"));
        assertEquals("https://localhost:8443/orders/" + orderId, gateway.get(0).get("returnUrl"));
        assertTrue(((String) view.get("sessionUrl")).endsWith("/payment/pay/" + row.get("gateway_payment_id")), "адрес страницы оплаты строит шлюз");
        assertFalse(view.containsKey("gatewayPaymentId"), "внешний идентификатор платежа наружу не отдаётся");
    }

    @Test
    void theGatewayKeyIsThePaymentIdSoAnotherPaymentOfThePlatformCannotCollide() throws Exception {
        UUID orderId = UUID.randomUUID();
        Response response = open(orderId, AMOUNT);
        assertEquals(201, response.status(), response.body());
        var gateway = gatewayPaymentsOf(orderId);
        assertEquals(response.json().get("paymentId"), gateway.get(0).get("idempotencyKey"));
    }

    @Test
    void aRepeatedCallWithTheSameKeyReturnsTheSavedAnswerAndTheGatewayIsNotCalledAgain() throws Exception {
        UUID orderId = UUID.randomUUID();
        String key = key();
        Response first = open(orders, orderId, AMOUNT, key);
        Response again = open(orders, orderId, AMOUNT, key);

        assertEquals(201, first.status(), first.body());
        assertEquals(201, again.status(), again.body());
        assertEquals("true", again.header("Idempotency-Replayed"));
        // ответ хранится в jsonb: порядок полей и пробелы другие, поэтому сравниваются документы, а не строки
        assertEquals(first.json(), again.json());
        assertEquals(1, gatewayPaymentsOf(orderId).size());
    }

    @Test
    void aRepeatedCallForTheSameOrderWithANewKeyReturnsTheSameSession() throws Exception {
        UUID orderId = UUID.randomUUID();
        Response first = open(orderId, AMOUNT);
        Response again = open(orderId, AMOUNT);

        assertEquals(201, again.status(), again.body());
        assertEquals(first.json().get("paymentId"), again.json().get("paymentId"));
        assertEquals(first.json().get("sessionUrl"), again.json().get("sessionUrl"));
        assertEquals(1, paymentsOf(orderId), "один платёж на заказ (INV-11)");
        assertEquals(1, gatewayPaymentsOf(orderId).size());
    }

    @Test
    void theSameKeyWithAnotherBodyIs422() throws Exception {
        UUID orderId = UUID.randomUUID();
        String key = key();
        assertEquals(201, open(orders, orderId, AMOUNT, key).status());
        Response other = open(orders, orderId, AMOUNT + 100, key);
        assertEquals(422, other.status(), other.body());
        assertEquals("idempotency-key-reuse", code(other));
    }

    @Test
    void anotherAmountForTheSameOrderIs409AndThePaymentStaysTheSame() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertEquals(201, open(orderId, AMOUNT).status());
        Response other = open(orderId, AMOUNT + 100);
        assertEquals(409, other.status(), other.body());
        assertEquals("state-conflict", code(other));
        assertEquals(AMOUNT, ((Number) payment(orderId).get("amount")).longValue());
    }

    @Test
    void whenTheGatewayDoesNotAnswerTheAnswerIs503AndTheRowStaysWithoutAnExternalId() throws Exception {
        UUID orderId = UUID.randomUUID();
        Response response = stubs.supplyInMode("payment", Map.of("scenario", "hold", "create", Map.of("failFirst", -1, "failure", "http_503")), () -> {
            try {
                return open(orderId, AMOUNT);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(503, response.status(), response.body());
        assertEquals("payment-session-unavailable", code(response));
        assertNotNull(response.header("Retry-After"));
        Map<String, Object> row = payment(orderId);
        assertNotNull(row, "платёж записан до вызова шлюза");
        assertEquals("created", row.get("status"));
        assertNull(row.get("gateway_payment_id"));
        assertNull(row.get("session_url"));
        assertTrue(gatewayPaymentsOf(orderId).isEmpty(), "у шлюза платежа нет");
    }

    @Tag("TC-040")
    @Test
    void afterTheGatewayRecoveredARepeatContinuesWithTheSameRow() throws Exception {
        UUID orderId = UUID.randomUUID();
        stubs.setMode("payment", Map.of("scenario", "hold", "create", Map.of("failFirst", -1, "failure", "http_503")));
        assertEquals(503, open(orderId, AMOUNT).status());
        Object paymentId = payment(orderId).get("id");
        stubs.setMode("payment", Map.of("scenario", "hold"));

        Response again = open(orderId, AMOUNT);
        assertEquals(201, again.status(), again.body());
        assertEquals(paymentId.toString(), again.json().get("paymentId"), "строка та же, второй платёж не заводится");
        assertEquals(1, paymentsOf(orderId));
        assertEquals(1, gatewayPaymentsOf(orderId).size());
    }

    @Tag("TC-040")
    @Test
    void aLostGatewayAnswerIsRepeatedByTheClientWithTheSameKeyAndGivesOnePayment() throws Exception {
        UUID orderId = UUID.randomUUID();
        Response response = stubs.supplyInMode("payment", Map.of("scenario", "hold", "create", Map.of("loseResponseFirst", 1)), () -> {
            try {
                return open(orderId, AMOUNT);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(201, response.status(), "клиент шлюза повторил вызов с тем же ключом: " + response.body());
        assertEquals(1, gatewayPaymentsOf(orderId).size(), "ответ потерян, а платёж у шлюза один");
        assertEquals(1, paymentsOf(orderId));
    }

    @Test
    void aPaymentInAnotherStatusIs409() throws Exception {
        UUID orderId = UUID.randomUUID();
        assertEquals(201, open(orderId, AMOUNT).status());
        db.update("update payments.payment set status = 'rejected', rejected_at = now() where order_id = ?", orderId);
        Response response = open(orderId, AMOUNT);
        assertEquals(409, response.status(), response.body());
        assertEquals("state-conflict", code(response));
        assertEquals("rejected", response.json().get("currentStatus"));
    }

    @Test
    void aBodyThatBreaksTheContractIs422WithPointers() throws Exception {
        Response response = orders.post(SESSIONS, "{\"orderId\":\"не-идентификатор\",\"amount\":{\"amount\":0,\"currency\":\"USD\"}}",
                "Idempotency-Key", key());
        assertEquals(422, response.status(), response.body());
        assertEquals("validation-failed", code(response));
        assertTrue(response.body().contains("/orderId") && response.body().contains("/amount/amount") && response.body().contains("/sessionExpiresAt"));
    }

    @Test
    void aSessionOutsideTheLimitsOfTheReservationIs422() throws Exception {
        UUID orderId = UUID.randomUUID();
        Response past = orders.post(SESSIONS, openBody(orderId, AMOUNT, Instant.now().minusSeconds(5)), "Idempotency-Key", key());
        assertEquals(422, past.status(), past.body());
        Response tooLong = orders.post(SESSIONS, openBody(orderId, AMOUNT, Instant.now().plus(Duration.ofHours(1))), "Idempotency-Key", key());
        assertEquals(422, tooLong.status(), tooLong.body());
        assertEquals(0, paymentsOf(orderId), "ничего не записано");
    }

    @Test
    void aCallWithoutTheIdempotencyKeyIs400() throws Exception {
        Response response = orders.post(SESSIONS, openBody(UUID.randomUUID(), AMOUNT, sessionEnd()));
        assertEquals(400, response.status(), response.body());
        assertEquals("idempotency-key-required", code(response));
    }

    // ---------------------------------------------------------------------------------------------- права вызывающих (ST-02, ST-08)

    @Test
    void onlyTheOrderServiceMayOpenASession() throws Exception {
        Response foreign = open(delivery, UUID.randomUUID(), AMOUNT, key());
        assertEquals(403, foreign.status(), foreign.body());
        assertEquals("forbidden", code(foreign));
    }

    @Test
    void aCallWithoutAClientCertificateNeverReachesTheHandler() {
        StandHttp anonymous = StandHttp.withoutCertificate(PORT);
        assertThrows(IOException.class, () -> anonymous.post(SESSIONS, openBody(UUID.randomUUID(), AMOUNT, sessionEnd()), "Idempotency-Key", key()));
    }

    /** ST-08: сертификат чужого центра с именем {@code order-service} не открывает платёжную сессию, платёж не создан ни у платформы, ни у шлюза. */
    @Test
    void aCertificateOfAForeignAuthorityWithTheNameOfTheOrderServiceNeverReachesTheHandler() {
        UUID orderId = UUID.randomUUID();
        StandHttp impostor = StandHttp.withForeignCertificate("order-service", PORT);
        assertThrows(IOException.class, () -> impostor.post(SESSIONS, openBody(orderId, AMOUNT, sessionEnd()), "Idempotency-Key", key()));
        assertEquals(0, paymentsOf(orderId), "ничего не записано");
        assertEquals(0, gatewayPaymentsOf(orderId).size(), "шлюз платежа не видел");
    }
}
