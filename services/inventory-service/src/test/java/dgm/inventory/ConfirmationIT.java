package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.reservation.timer.RedisExpiryQueue;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Подтверждение резерва и выдача значений на стенде (шаг 6 среза 2; SM-08/T2, SM-03/T4, INV-04, INV-05, INV-06, ADR-013, SEQ-02, U17,
 * ST-08, US-5.2, US-5.6, US-6.1): активный резерв становится использованным, поздняя оплата создаёт резерв заново или получает отказ,
 * значения ключей отдаёт только {@code delivery-service} и только по оплаченному заказу. Гонки подтверждения и истечения проверяет
 * {@link ReservationConcurrencyIT}.
 */
class ConfirmationIT extends ReservationStandIT {

    // ---------------------------------------------------------------- confirmReservation

    @Test
    void anActiveReservationIsConfirmedAndItsKeysAreIssued() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        Response reserved = reserve(orderId, pool.productId(), 3);
        assertEquals(201, reserved.status(), reserved.body());
        String reservationId = (String) reserved.json().get("reservationId");

        Response response = confirm(orderId, pool.productId(), 3);
        assertEquals(200, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        Map<String, Object> body = response.json();
        assertEquals(reservationId, body.get("reservationId"));
        assertEquals("used", body.get("status"));
        assertEquals(false, body.get("reReserved"));
        assertEquals(3, body.get("quantity"));

        Map<String, Object> row = reservation(orderId);
        assertEquals("used", row.get("status"));
        assertNotNull(row.get("used_at"), "время использования записано (ck_reservation_used_state)");
        assertEquals(3, keysOfOrder(orderId, "issued"));
        assertEquals(0, keysOfOrder(orderId, "reserved"));
        assertEquals(2, keys(pool.productId(), "free"));
        assertEquals(3, db.queryForObject("select count(*) from inventory.key where order_id = ? and status = 'issued' and issued_at is not null "
                + "and reservation_id = ?::uuid", Integer.class, orderId, reservationId), "выданные ключи остаются за заказом и резервом");

        List<Map<String, Object>> stock = stockEvents(pool.productId());
        assertEquals(3, stock.size(), "загрузка, резерв, подтверждение");
        assertEquals(2, stock.get(2).get("available"), "подтверждение остаток не меняет");
        assertEquals(((Number) stock.get(1).get("version")).longValue() + 1, ((Number) stock.get(2).get("version")).longValue());
        assertTrue(redis.zrangeByScore(RedisExpiryQueue.KEY, 0, Long.MAX_VALUE, 10_000).stream().noneMatch(reservationId::equals),
                "таймер использованного резерва снят");
    }

    @Test
    void aRepeatedConfirmationWithTheSameOrANewKeyReturnsTheSameReservationAndChangesNothing() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 2).status());
        String firstKey = key();
        Response first = confirm(orders, orderId, pool.productId(), 2, firstKey);
        assertEquals(200, first.status(), first.body());
        long version = stockVersion(pool.productId());
        int outbox = stockEvents(pool.productId()).size();

        Response replay = confirm(orders, orderId, pool.productId(), 2, firstKey);
        assertEquals(200, replay.status(), replay.body());
        assertEquals("true", replay.header("Idempotency-Replayed"));
        assertEquals(first.body(), replay.body(), "охранник идемпотентности возвращает прежний ответ");

        Response another = confirm(orders, orderId, pool.productId(), 2, key());
        assertEquals(200, another.status(), another.body());
        assertEquals(first.json().get("reservationId"), another.json().get("reservationId"));
        assertEquals("used", another.json().get("status"));
        assertEquals(1, reservationsOf(orderId));
        assertEquals(2, keysOfOrder(orderId, "issued"));
        assertEquals(version, stockVersion(pool.productId()));
        assertEquals(outbox, stockEvents(pool.productId()).size());
    }

    @Test
    void paymentAfterTheReservationExpiredCreatesANewUsedReservationWhenKeysAreStillFree() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 3).status());
        assertTrue(await(LIMIT, () -> "released".equals(status(orderId))), "резерв снят по сроку");
        assertEquals(5, keys(pool.productId(), "free"));
        String expiredId = String.valueOf(reservation(orderId).get("id"));

        Response response = confirm(orderId, pool.productId(), 3);
        assertEquals(200, response.status(), response.body());
        assertEquals(true, response.json().get("reReserved"), "поздняя оплата: резерв создан заново (SEQ-02)");
        assertEquals("used", response.json().get("status"));
        assertNotEquals(expiredId, response.json().get("reservationId"));

        assertEquals(2, reservationsOf(orderId));
        assertEquals(1, db.queryForObject("select count(*) from inventory.reservation where order_id = ? and status = 'released' and release_reason = 'expired'",
                Integer.class, orderId), "прежний резерв остался снятым по сроку");
        assertEquals(1, db.queryForObject("select count(*) from inventory.reservation where order_id = ? and status = 'used'", Integer.class, orderId));
        assertEquals(0, db.queryForObject("select count(*) from inventory.reservation where order_id = ? and status = 'active'", Integer.class, orderId));
        assertEquals(3, keysOfOrder(orderId, "issued"));
        assertEquals(2, keys(pool.productId(), "free"));

        // тот же вызов ещё раз возвращает использованный резерв, третьего резерва нет
        Response again = confirm(orders, orderId, pool.productId(), 3, key());
        assertEquals(200, again.status(), again.body());
        assertEquals(response.json().get("reservationId"), again.json().get("reservationId"));
        assertEquals(2, reservationsOf(orderId));
    }

    @Test
    void latePaymentWithTooFewFreeKeysAnswers409AndChangesNothing() throws Exception {
        Pool pool = pool(5);
        UUID late = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        assertEquals(201, reserve(late, pool.productId(), 3).status());
        assertTrue(await(LIMIT, () -> "released".equals(status(late))));
        assertEquals(201, reserve(other, pool.productId(), 4).status(), "ключи занял другой заказ");
        long version = stockVersion(pool.productId());
        int outbox = stockEvents(pool.productId()).size();

        Response response = confirm(late, pool.productId(), 3);
        assertEquals(409, response.status(), response.body());
        assertEquals("insufficient-stock", code(response));
        assertEquals(1, response.json().get("available"));
        assertEquals(1, reservationsOf(late), "нового резерва нет, прежний остался снятым");
        assertEquals("released", status(late));
        assertEquals(0, keysOfOrder(late, "issued") + keysOfOrder(late, "reserved"));
        assertEquals(4, keysOfOrder(other, "reserved"), "ключи другого заказа не тронуты");
        assertEquals(version, stockVersion(pool.productId()));
        assertEquals(outbox, stockEvents(pool.productId()).size());
    }

    @Test
    void paymentForACancelledOrderIsConfirmedAsLatePayment() throws Exception {
        Pool pool = pool(4);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 2).status());
        UUID cancelled = publishCancelled(orderId, "order_cancelled");
        assertTrue(await(LIMIT, () -> processed(cancelled)));
        assertEquals("released", status(orderId));

        Response response = confirm(orderId, pool.productId(), 2);
        assertEquals(200, response.status(), response.body());
        assertEquals(true, response.json().get("reReserved"));
        assertEquals(2, keysOfOrder(orderId, "issued"));
        assertEquals(2, keys(pool.productId(), "free"));
    }

    @Test
    void anOrderWithoutAReservationAnUnknownProductAndAMismatchAreRefused() throws Exception {
        Pool pool = pool(3);
        UUID orderId = UUID.randomUUID();
        Response none = confirm(orderId, pool.productId(), 1);
        assertEquals(404, none.status(), none.body());
        assertEquals("not-found", code(none));
        assertEquals(404, confirm(orderId, UUID.randomUUID(), 1).status());

        assertEquals(201, reserve(orderId, pool.productId(), 2).status());
        Response quantity = confirm(orderId, pool.productId(), 1);
        assertEquals(409, quantity.status(), quantity.body());
        assertEquals("state-conflict", code(quantity));
        Pool another = pool(2);
        assertEquals(409, confirm(orderId, another.productId(), 2).status());
        assertEquals("active", status(orderId), "отказ резерв не меняет");
        assertEquals(2, keysOfOrder(orderId, "reserved"));

        assertEquals(422, orders.post(CONFIRM, "{\"orderId\":\"" + orderId + "\",\"productId\":\"" + pool.productId() + "\",\"quantity\":0}",
                "Idempotency-Key", key()).status());
        assertEquals(400, orders.post(CONFIRM, "{\"orderId\":", "Idempotency-Key", key()).status());
        assertEquals(400, orders.post(CONFIRM, body(orderId, pool.productId(), 2)).status(), "без Idempotency-Key");
    }

    // ---------------------------------------------------------------- getKeyValues

    @Test
    void deliveryServiceGetsTheDecryptedValuesOfAPaidOrderInTheOrderOfIssue() throws Exception {
        Pool pool = pool(5);
        UUID orderId = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 3).status());
        assertEquals(201, reserve(bystander, pool.productId(), 2).status());

        Response early = keyValues(delivery, orderId);
        assertEquals(409, early.status(), early.body());
        assertEquals("state-conflict", code(early));
        assertFalse(early.body().contains(pool.values().get(0)), "до оплаты значений нет");

        assertEquals(200, confirm(orderId, pool.productId(), 3).status());
        Response response = keyValues(delivery, orderId);
        assertEquals(200, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals(orderId.toString(), response.json().get("orderId"));
        List<Map<String, Object>> keys = items(response);
        assertEquals(3, keys.size());

        List<String> expectedIds = db.queryForList("select id::text from inventory.key where order_id = ? order by issued_at, id", String.class, orderId);
        assertEquals(expectedIds, keys.stream().map(k -> (String) k.get("keyId")).toList(), "порядок выдачи");
        Set<String> values = new HashSet<>();
        for (Map<String, Object> key : keys) {
            values.add((String) key.get("value"));
        }
        assertEquals(3, values.size());
        assertTrue(pool.values().containsAll(values), "значения это загруженные продавцом");

        // ключи чужого заказа не попадают в ответ, у соседа ключи ещё зарезервированы и не отдаются
        Response neighbour = keyValues(delivery, bystander);
        assertEquals(409, neighbour.status(), neighbour.body());
        for (String value : values) {
            assertFalse(neighbour.body().contains(value), "значение чужого заказа в ответе");
        }
        assertEquals(200, confirm(bystander, pool.productId(), 2).status());
        Response second = keyValues(delivery, bystander);
        assertEquals(200, second.status(), second.body());
        for (Map<String, Object> key : items(second)) {
            assertFalse(values.contains(key.get("value")), "ни один ключ не выдан двум заказам");
        }
        assertEquals(5, db.queryForObject("select count(distinct hmac) from inventory.key where product_id = ? and status = 'issued'", Integer.class,
                pool.productId()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Response response) {
        List<Map<String, Object>> keys = new ArrayList<>();
        for (Object item : (List<?>) response.json().get("keys")) {
            keys.add((Map<String, Object>) item);
        }
        return keys;
    }

    @Test
    void anOrderWithoutKeysIs404AndABadOrderIdIs400() throws Exception {
        Response unknown = keyValues(delivery, UUID.randomUUID());
        assertEquals(404, unknown.status(), unknown.body());
        assertEquals("not-found", code(unknown));
        assertEquals(400, delivery.get(KEY_VALUES).status());
        assertEquals(400, delivery.get(KEY_VALUES + "?orderId=не-идентификатор").status());
    }

    // ---------------------------------------------------------------- права вызывающих (ST-02, ST-08)

    @Test
    void everyInternalOperationAcceptsOnlyItsCallers() throws Exception {
        Pool pool = pool(3);
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, pool.productId(), 1).status());
        assertEquals(200, confirm(orderId, pool.productId(), 1).status());
        for (String caller : List.of("delivery-service", "payment-service", "api-gateway", "platform-service", "catalog-service")) {
            UUID other = UUID.randomUUID();
            Response refused = confirm(StandHttp.asService(caller, PORT), other, pool.productId(), 1, other.toString());
            assertEquals(403, refused.status(), "confirmReservation от " + caller + ": " + refused.body());
            assertNull(reservation(other));
        }
        for (String caller : List.of("order-service", "payment-service", "api-gateway", "platform-service", "catalog-service")) {
            Response refused = keyValues(StandHttp.asService(caller, PORT), orderId);
            assertEquals(403, refused.status(), "getKeyValues от " + caller + ": " + refused.body());
            assertEquals("forbidden", code(refused));
            assertFalse(refused.body().contains(pool.values().get(0)));
        }
        assertEquals(200, keyValues(delivery, orderId).status(), "delivery-service получает значения");
    }

    @Test
    void aClientWithoutAPlatformCertificateIsRefusedOnEveryInternalOperation() {
        UUID orderId = UUID.randomUUID();
        assertThrows(IOException.class, () -> StandHttp.withoutCertificate(PORT).post(RESERVATIONS, body(orderId, orderId, 1), "Idempotency-Key", key()));
        assertThrows(IOException.class, () -> StandHttp.withoutCertificate(PORT).post(CONFIRM, body(orderId, orderId, 1), "Idempotency-Key", key()));
        assertThrows(IOException.class, () -> StandHttp.withoutCertificate(PORT).get(KEY_VALUES + "?orderId=" + orderId));
    }
}
