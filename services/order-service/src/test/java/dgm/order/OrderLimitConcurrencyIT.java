package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.Concurrent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Гонки оформления заказа (ADR-006, FT-5.1, Р-142, NFT-2.3): десять одновременных запросов одного покупателя с разными ключами и десять с
 * одним ключом. Доказательство это запросы к базе и записанные вызовы соседей, а не только ответы потоков. Задание CI {@code concurrency}
 * повторяет тесты с тегом {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class OrderLimitConcurrencyIT extends OrderStandIT {

    private static final int CALLS = 10;
    private static final long PRICE = 99_900;

    private final UUID seller = UUID.randomUUID();

    @Tag("TC-042")
    @Test
    void tenParallelOrdersOfOneBuyerGiveExactlyThreeOrdersAndSevenRefusalsByTheLimit() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID product = NEIGHBOURS.product(seller, "Игра для гонки", PRICE, 20);
        String token = buyerToken(buyer);

        List<Response> responses = Concurrent.race(CALLS, call -> create(token, product, 1));

        int created = 0;
        int refused = 0;
        Set<Object> ids = new HashSet<>();
        for (Response response : responses) {
            if (response.status() == 201) {
                created++;
                ids.add(response.json().get("orderId"));
            } else {
                assertEquals(409, response.status(), response.body());
                assertEquals("unpaid-orders-limit", code(response));
                refused++;
            }
        }
        assertEquals(3, created, "ровно три заказа (orders.max-unpaid)");
        assertEquals(7, refused);
        assertEquals(3, ids.size());

        assertEquals(3, ordersOf(buyer).size(), "в базе три заказа покупателя, лишних строк нет");
        assertEquals(3, countOrders(buyer, "awaiting_payment"));
        assertEquals(0, countOrders(buyer, "created", "cancelled"), "отказанные по лимиту заказов не создавали");
        assertEquals(3, NEIGHBOURS.calls("inventory").size(), "ключи резервировались только для принятых заказов");
        assertEquals(3, NEIGHBOURS.calls("payment").size());
        assertEquals(17, NEIGHBOURS.keysLeft(product));
        for (Object id : ids) {
            assertEquals(1, events(id.toString(), "order.created").size(), "по одному событию order.created на заказ");
        }
    }

    @Tag("TC-042")
    @Test
    void tenParallelRequestsWithOneKeyMakeOneOrderAndTheRestWaitOrReplay() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID product = NEIGHBOURS.product(seller, "Игра для гонки с ключом", PRICE, 20);
        String token = buyerToken(buyer);
        String key = key();

        List<Response> responses = Concurrent.race(CALLS, call -> create(token, product, 1, key));

        Set<Object> ids = new HashSet<>();
        int created = 0;
        for (Response response : responses) {
            if (response.status() == 201) {
                created++;
                ids.add(response.json().get("orderId"));
            } else {
                assertEquals(409, response.status(), response.body());
                assertEquals("request-in-progress", code(response));
            }
        }
        assertTrue(created >= 1, "первый запрос получил заказ");
        assertEquals(1, ids.size(), "все успешные ответы это один и тот же заказ");
        assertEquals(1, ordersOf(buyer).size());
        assertEquals(1, NEIGHBOURS.calls("inventory").size());
        assertEquals(1, NEIGHBOURS.calls("payment").size());
        assertEquals(19, NEIGHBOURS.keysLeft(product));

        Response after = create(token, product, 1, key);
        assertEquals(201, after.status(), after.body());
        assertEquals("true", after.header("Idempotency-Replayed"), "после гонки тот же ключ отдаёт сохранённый ответ");
        assertEquals(ids.iterator().next(), after.json().get("orderId"));
        assertEquals(1, ordersOf(buyer).size());
    }
}
