package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.reservation.service.ReservationService;
import dgm.inventory.inventory.reservation.service.ReservationService.Detected;
import dgm.inventory.inventory.reservation.timer.RedisExpiryQueue;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.Concurrent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Гонки за ключи и за резерв (ADR-007, ADR-013, INV-01, INV-04, INV-05, INV-06, NFT-2.2): параллельные заказы на последние ключи пула
 * и подтверждение резерва одновременно с его истечением. Потоки стартуют по общей защёлке ({@link Concurrent#race}), доказательство это
 * запросы к базе ({@code GROUP BY … HAVING}, сверка числа ключей с резервами), а не только ответы потоков. Задание CI {@code concurrency}
 * повторяет тесты с тегом {@code concurrency} 50 раз подряд (tools/ci/concurrency_series.sh). Пул соединений сервиса 7, поэтому
 * каждый поток обращается к сервису по HTTP, а прямых обращений к базе из потоков нет.
 */
@Tag("concurrency")
class ReservationConcurrencyIT extends ReservationStandIT {

    private static final int BUYERS = 20;

    @Autowired
    private ReservationService reservations;

    /** Резервы, у которых число закреплённых ключей не равно количеству: в базе такого быть не должно. */
    private static int reservationsWithWrongKeyCount(UUID productId) {
        Integer n = db.queryForObject("select count(*) from (select r.id from inventory.reservation r left join inventory.key k on k.reservation_id = r.id "
                + "where r.product_id = ? and r.status <> 'released' group by r.id, r.quantity having count(k.id) <> r.quantity) wrong", Integer.class, productId);
        return n == null ? 0 : n;
    }

    /** Ключи с привязкой к заказу у товара, где один заказ держит ключ больше одного раза или ключ привязан без резерва. */
    private static int keysBoundTwiceOrWithoutReservation(UUID productId) {
        Integer n = db.queryForObject("select count(*) from inventory.key where product_id = ? and status in ('reserved', 'issued') "
                + "and (order_id is null or reservation_id is null)", Integer.class, productId);
        return n == null ? 0 : n;
    }

    @Tag("TC-030")
    @Test
    void twentyBuyersForTheLastFiveKeysGetExactlyFiveReservations() throws Exception {
        Pool pool = pool(5);
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            orders.add(UUID.randomUUID());
        }
        List<Response> responses = Concurrent.race(BUYERS, buyer -> reserve(orders.get(buyer), pool.productId(), 1));

        int created = 0;
        int refused = 0;
        for (Response response : responses) {
            if (response.status() == 201) {
                created++;
            } else {
                refused++;
                assertEquals(409, response.status(), response.body());
                assertEquals("insufficient-stock", code(response));
            }
        }
        assertEquals(5, created, "ровно пять заказов получили ключ");
        assertEquals(BUYERS - 5, refused, "остальные получили отказ «нет остатка»");

        assertEquals(5, db.queryForObject("select count(*) from inventory.reservation where product_id = ?", Integer.class, pool.productId()));
        assertEquals(5, keys(pool.productId(), "reserved"));
        assertEquals(0, keys(pool.productId(), "free"));
        assertEquals(0, db.queryForObject("select count(*) from (select order_id from inventory.key where product_id = ? and order_id is not null "
                + "group by order_id having count(*) > 1) twice", Integer.class, pool.productId()), "ни один заказ не держит два ключа при количестве 1");
        assertEquals(0, db.queryForObject("select count(*) from (select reservation_id from inventory.key where product_id = ? and reservation_id is not null "
                + "group by reservation_id having count(*) > 1) twice", Integer.class, pool.productId()));
        assertEquals(0, reservationsWithWrongKeyCount(pool.productId()));
        assertEquals(0, keysBoundTwiceOrWithoutReservation(pool.productId()));

        List<Map<String, Object>> stock = stockEvents(pool.productId());
        assertEquals(6, stock.size(), "загрузка и пять резервов; отказы события не пишут");
        long start = ((Number) stock.get(0).get("version")).longValue();
        for (int i = 0; i < stock.size(); i++) {
            assertEquals(start + i, ((Number) stock.get(i).get("version")).longValue(), "версия растёт на единицу без пропусков");
            assertEquals(5 - Math.max(0, i), stock.get(i).get("available"), "остаток падает на единицу с каждым резервом");
        }
    }

    @Tag("TC-030")
    @Test
    void tenBuyersOfThreeKeysEachNeverHoldAPartialReservation() throws Exception {
        Pool pool = pool(10);
        int buyers = 10;
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            orders.add(UUID.randomUUID());
        }
        List<Response> responses = Concurrent.race(buyers, buyer -> reserve(orders.get(buyer), pool.productId(), 3));
        long created = responses.stream().filter(r -> r.status() == 201).count();
        assertEquals(3, created, "из десяти ключей получаются ровно три резерва по три ключа");
        for (Response response : responses) {
            if (response.status() != 201) {
                assertEquals(409, response.status(), response.body());
                assertEquals("insufficient-stock", code(response));
            }
        }
        assertEquals(9, keys(pool.productId(), "reserved"));
        assertEquals(1, keys(pool.productId(), "free"));
        assertEquals(0, reservationsWithWrongKeyCount(pool.productId()), "частичного резерва нет (INV-05)");
        assertEquals(3, db.queryForObject("select count(*) from inventory.reservation where product_id = ? and quantity = 3", Integer.class, pool.productId()));
    }

    @Tag("TC-031")
    @Test
    void paymentAndExpiryOfTheSameReservationAtTheDeadlineLeaveExactlyOneOutcomePerOrder() throws Exception {
        int orders = 4;
        Pool pool = pool(2 * orders + 2);
        List<UUID> orderIds = new ArrayList<>();
        List<UUID> reservationIds = new ArrayList<>();
        Instant earliest = null;
        for (int i = 0; i < orders; i++) {
            UUID orderId = UUID.randomUUID();
            orderIds.add(orderId);
            Response reserved = reserve(orderId, pool.productId(), 1);
            assertEquals(201, reserved.status(), reserved.body());
            reservationIds.add(UUID.fromString((String) reserved.json().get("reservationId")));
            Instant expiresAt = Instant.parse((String) reserved.json().get("expiresAt"));
            earliest = earliest == null || expiresAt.isBefore(earliest) ? expiresAt : earliest;
        }
        // Ждём границу срока: подтверждение и истечение стартуют вместе, пока резервы ещё не сняты ни таймером, ни сверкой
        long wait = earliest.toEpochMilli() - System.currentTimeMillis() - 15;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        List<Object> results = Concurrent.race(2 * orders, index -> {
            if (index % 2 == 0) {
                return confirm(orderIds.get(index / 2), pool.productId(), 1);
            }
            return reservations.expire(reservationIds.get(index / 2), Detected.TIMER);
        });

        for (int i = 0; i < orders; i++) {
            UUID orderId = orderIds.get(i);
            Response payment = (Response) results.get(2 * i);
            assertEquals(200, payment.status(), "оплата заказа " + i + " подтверждена в любом порядке: " + payment.body());
            assertEquals("used", payment.json().get("status"));
            boolean reReserved = (Boolean) payment.json().get("reReserved");

            // ровно один исход: использованный резерв с выданным ключом; активных нет (INV-06)
            assertEquals(1, db.queryForObject("select count(*) from inventory.reservation where order_id = ? and status = 'used'", Integer.class, orderId));
            assertEquals(0, db.queryForObject("select count(*) from inventory.reservation where order_id = ? and status = 'active'", Integer.class, orderId));
            assertEquals(1, keysOfOrder(orderId, "issued"), "у оплаченного заказа ровно один выданный ключ");
            assertEquals(0, keysOfOrder(orderId, "reserved"));
            int expired = events(orderId, "reservation.expired").size();
            assertTrue(expired <= 1, "событие истечения не больше одного");
            // истечение успело первым ровно тогда, когда оплата получила новый резерв
            assertEquals(reReserved, expired == 1, "поздняя оплата и событие reservation.expired идут вместе");
            assertEquals(reReserved ? 2 : 1, reservationsOf(orderId));
        }
        int issued = keys(pool.productId(), "issued");
        assertEquals(orders, issued, "выданных ключей столько же, сколько оплаченных заказов");
        assertEquals(pool.values().size() - orders, keys(pool.productId(), "free"), "ни один ключ не потерян и не удвоен");
        assertEquals(0, keys(pool.productId(), "reserved"));
        assertEquals(0, reservationsWithWrongKeyCount(pool.productId()));

        // таймеры использованных резервов сняты, поздний срабатывающий таймер ничего не меняет
        List<String> queued = redis.zrangeByScore(RedisExpiryQueue.KEY, 0, Long.MAX_VALUE, 10_000);
        assertTrue(await(LIMIT, () -> {
            List<String> now = redis.zrangeByScore(RedisExpiryQueue.KEY, 0, Long.MAX_VALUE, 10_000);
            for (UUID orderId : orderIds) {
                List<Object> ids = db.queryForList("select id from inventory.reservation where order_id = ?", orderId).stream().map(row -> row.get("id")).toList();
                for (Object id : ids) {
                    if (now.contains(String.valueOf(id))) {
                        return false;
                    }
                }
            }
            return true;
        }), "в очереди сроков нет резервов этих заказов (" + queued.size() + " записей других заказов)");
        for (UUID orderId : orderIds) {
            assertEquals(1, db.queryForObject("select count(*) from inventory.reservation where order_id = ? and status = 'used'", Integer.class, orderId));
        }
    }
}
