package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Concurrent;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Гонка ответа продавца и тайм-аута на стенде (ADR-028, Р-190, TC-968): у каждого запроса исход один, и он один из двух, ответ принят и ключ сохранён
 * либо запрос закрыт тайм-аутом и выдача отменена. Ответ, начатый до конца окна, имеет право закончиться, даже если ключ сохраняется дольше окна:
 * аренда не даёт тайм-ауту закрыть запрос посреди приёма. Запросы заводятся строками базы (без Kafka), срок задаёт тест. Задание CI {@code concurrency}
 * повторяет тесты с тегом {@code concurrency} 50 раз подряд, поэтому каждый запуск короткий.
 */
@Tag("concurrency")
class SellerAnswerConcurrencyIT extends DeliveryStandIT {

    /** Запросов в одной гонке: каждый получает свой ответ, проходы тайм-аута общие. */
    private static final int REQUESTS = 8;

    private record Seeded(Placed order, UUID seller, UUID requestId) {
    }

    /** Выдача в очереди и запрос продавцу «отправлен» со сроком {@code deadline}; создан давно, чтобы срок был позже создания. */
    private Seeded seedRequest(int quantity, Instant deadline) {
        Placed p = seed(quantity);
        UUID seller = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        db.update("insert into delivery.seller_request (delivery_id, request_id, seller_id, product_id, order_number, status, deadline_at, attempts, created_at, "
                + "updated_at) values (?, ?, ?, ?, 1001, 'requested', ?, 1, now() - interval '10 minutes', now() - interval '10 minutes')", idOf(p), requestId,
                seller, UUID.randomUUID(), OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC));
        INVENTORY.expectSellerKeys(p.orderId(), quantity);
        return new Seeded(p, seller, requestId);
    }

    private static void sleepUntil(Instant at) {
        pause(Math.max(0, Duration.between(Instant.now(), at).toMillis()));
    }

    private static String status(Seeded s) {
        return (String) SellerRequestIT.requestOf(s.order().orderId()).get("status");
    }

    private static Response answer(Seeded s, List<String> keys) throws Exception {
        return SellerRequestIT.answer(s.seller(), s.requestId(), Json.write(Map.of("keys", keys)));
    }

    // ------------------------------------------------------------------------------------------------ TC-968

    @Tag("TC-968")
    @Test
    void theAnswerAndTheTimeoutAtTheEndOfTheWindowGiveExactlyOneOutcomeForEveryRequest() throws Exception {
        Instant deadline = Instant.now().plusMillis(2500);
        List<Seeded> requests = new ArrayList<>();
        List<List<String>> keys = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            requests.add(seedRequest(1, deadline));
            keys.add(SellerRequestIT.sellerKeys(1));
        }
        sleepUntil(deadline.minusMillis(20));

        List<Object> results = Concurrent.<Object>race(REQUESTS + 2, i -> {
            if (i < REQUESTS) {
                return answer(requests.get(i), keys.get(i)).status();
            }
            return timeoutJob.runOnce();
        });
        sleepUntil(deadline.plusMillis(50));
        timeoutJob.runOnce();

        int answered = 0;
        int timedOut = 0;
        for (int i = 0; i < REQUESTS; i++) {
            Seeded s = requests.get(i);
            int httpStatus = (Integer) results.get(i);
            String state = status(s);
            List<InventoryStub.SellerKeysCall> stored = INVENTORY.sellerCalls(s.order().orderId());
            int timeoutEvents = events(s.order(), "delivery.seller-timeout").size();
            if ("answered".equals(state)) {
                answered++;
                assertEquals(200, httpStatus, "ответ принят, а продавец получил отказ: запрос " + s.requestId());
                assertEquals(1, stored.size(), "ключ принят и сохранён один раз");
                assertEquals(keys.get(i), stored.get(0).keys());
                assertEquals("queued", status(s.order()), "выдача ждёт диспетчера");
                assertEquals(0, timeoutEvents, "запрос получил ответ, события тайм-аута быть не должно: запрос " + s.requestId());
            } else {
                timedOut++;
                assertEquals("timeout", state, "запрос остался открытым после окна: " + s.requestId());
                assertEquals(409, httpStatus, "запрос закрыт тайм-аутом, а продавцу ответ принят: " + s.requestId());
                assertEquals(0, stored.size(), "запрос закрыт тайм-аутом, а ключ сохранён: " + s.requestId());
                assertEquals("failed", status(s.order()));
                assertEquals(1, timeoutEvents, "тайм-аут опубликован не один раз: запрос " + s.requestId());
            }
            assertEquals(0, events(s.order(), "delivery.failed").size());
            assertEquals(false, SellerRequestIT.requestOf(s.order().orderId()).get("leased"), "аренда снята");
        }
        assertEquals(REQUESTS, answered + timedOut);
        assertTrue(results.subList(REQUESTS, REQUESTS + 2).stream().allMatch(r -> (Integer) r >= 0));
    }

    @Tag("TC-968")
    @Test
    void anAnswerStartedBeforeTheEndOfTheWindowKeepsItsKeyWhileTheStoreOutlastsTheWindowAndTheTimeoutLeavesTheRequestAlone() throws Exception {
        Instant deadline = Instant.now().plusMillis(2500);
        Seeded s = seedRequest(2, deadline);
        List<String> keys = SellerRequestIT.sellerKeys(2);
        // сохранение ключей длится дольше конца окна
        INVENTORY.beforeStore(() -> pause(900));
        sleepUntil(deadline.minusMillis(400));

        List<Object> results = Concurrent.<Object>race(2, i -> {
            if (i == 0) {
                return answer(s, keys).status();
            }
            sleepUntil(deadline.plusMillis(200));
            return timeoutJob.runOnce();
        });

        assertEquals(200, results.get(0), "ответ, начатый до конца окна, принят");
        assertEquals(0, results.get(1), "тайм-аут не закрыл запрос с арендой");
        assertEquals("answered", status(s));
        assertEquals(1, INVENTORY.sellerCalls(s.order().orderId()).size());
        assertEquals(0, events(s.order(), "delivery.seller-timeout").size());
        assertEquals("queued", status(s.order()));
        assertTrue(drive(LIMIT, () -> "sent".equals(status(s.order()))), "письмо не ушло");
        assertSent(s.order(), keys);
        assertEquals(0, timeoutJob.runOnce(), "после ответа закрывать нечего");
    }

    @Tag("TC-968")
    @Test
    void anAnswerStartedAfterTheEndOfTheWindowIsRefusedAndTheTimeoutClosesTheRequestOnce() throws Exception {
        Instant deadline = Instant.now().plusMillis(1500);
        Seeded s = seedRequest(1, deadline);
        sleepUntil(deadline.plusMillis(100));

        List<Object> results = Concurrent.<Object>race(3, i -> i == 0 ? (Object) answer(s, SellerRequestIT.sellerKeys(1)).status() : (Object) timeoutJob.runOnce());

        assertEquals(409, results.get(0), "ответ после окна не принят");
        assertEquals(1, (Integer) results.get(1) + (Integer) results.get(2), "два прохода тайм-аута закрыли запрос ровно один раз: " + results);
        assertEquals("timeout", status(s));
        assertEquals(0, INVENTORY.sellerCalls(s.order().orderId()).size());
        assertEquals(1, events(s.order(), "delivery.seller-timeout").size());
        assertFalse("sent".equals(status(s.order())));
    }
}
