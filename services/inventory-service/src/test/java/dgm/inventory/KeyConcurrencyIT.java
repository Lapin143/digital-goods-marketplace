package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.testing.Concurrent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Загрузка ключей при одновременных запросах (шаг 15 фазы 4, INV-03, ADR-009, US-4.2, US-4.3). Участников гонки запускает
 * {@link Concurrent#race}: все стартуют по общей защёлке, результат проверяется запросом к базе ({@code GROUP BY … HAVING count(*) > 1}),
 * а не только ответами. Участников не больше шести: пул соединений сервиса семь, а запрос держит соединение, пока ждёт блокировку
 * строки товара. Задание CI {@code concurrency} повторяет тесты с тегом {@code concurrency} 50 раз подряд (tools/ci/concurrency_series.sh).
 */
@Tag("concurrency")
class KeyConcurrencyIT extends KeyStandIT {

    private static final int SESSIONS = 6;

    @Tag("TC-404")
    @Test
    void theSameValueUploadedBySeveralSessionsAtOnceIsAcceptedExactlyOnce() {
        Seller seller = new Seller();
        UUID productId = seller.product();
        List<Response> responses = Concurrent.race(SESSIONS, session -> batch(seller, productId, "RACE-0001"));
        int accepted = 0;
        long rejectedAsDuplicate = 0;
        for (Response response : responses) {
            assertEquals(200, response.status(), response.body());
            accepted += (Integer) response.json().get("accepted");
            rejectedAsDuplicate += rejected(response).stream().filter(r -> "duplicate_in_pool".equals(r.get("reason"))).count();
        }
        assertEquals(1, accepted, "ровно одна сессия приняла значение");
        assertEquals(SESSIONS - 1, rejectedAsDuplicate, "остальные получили отказ «дубль в пуле»");
        assertEquals(1, count(productId));
        assertEquals(0, duplicateValues(productId), "в базе нет повторов значения");
        assertEquals(1, stockEvents(productId).size(), "событие остатка одно: принявшая сессия");
    }

    @Tag("TC-404")
    @Test
    void overlappingListsFromSeveralSessionsLeaveEveryValueOnceAndTheStockExact() {
        Seller seller = new Seller();
        UUID productId = seller.product();
        // Сессия i грузит значения [i*40, i*40+150): окна пересекаются, всего различных значений 5*40+150 = 350
        int window = 150;
        int step = 40;
        int distinct = (SESSIONS - 1) * step + window;
        List<Response> responses = Concurrent.race(SESSIONS, session -> {
            String[] keys = new String[window];
            for (int k = 0; k < window; k++) {
                keys[k] = "OVL-" + (session * step + k);
            }
            return batch(seller, productId, keys);
        });
        int accepted = 0;
        int uploadsWithNewKeys = 0;
        for (Response response : responses) {
            assertEquals(200, response.status(), response.body());
            int taken = (Integer) response.json().get("accepted");
            int refused = (Integer) response.json().get("rejectedTotal");
            assertEquals(window, taken + refused, "каждая строка либо принята, либо названа в отчёте");
            accepted += taken;
            if (taken > 0) {
                uploadsWithNewKeys++;
            }
        }
        assertEquals(distinct, accepted, "каждое различное значение принято ровно одной сессией");
        assertEquals(distinct, count(productId));
        assertEquals(0, duplicateValues(productId));

        List<Map<String, Object>> events = stockEvents(productId);
        // Окно средней сессии могло целиком лечь на уже принятые значения соседей: такая загрузка ничего не меняет и события не пишет
        assertEquals(uploadsWithNewKeys, events.size(), "событие на каждую загрузку, принявшую хотя бы один ключ");
        int previous = 0;
        long previousVersion = 0;
        for (Map<String, Object> event : events) {
            int available = (Integer) event.get("available");
            long version = ((Number) event.get("version")).longValue();
            assertTrue(available > previous, "остаток в событиях растёт: загрузки одного товара идут по очереди");
            assertTrue(version >= previousVersion, "версия события не убывает");
            previous = available;
            previousVersion = version;
        }
        assertEquals(distinct, previous, "последнее событие несёт итоговый остаток");
    }

    @Tag("TC-404")
    @Test
    void aListAndAFileWithTheSameValuesAtOnceAreOneUploadInEffect() {
        Seller seller = new Seller();
        UUID productId = seller.product();
        int values = 60;
        String[] keys = new String[values];
        StringBuilder csv = new StringBuilder();
        for (int k = 0; k < values; k++) {
            keys[k] = "MIX-" + k;
            csv.append(keys[k]).append('\n');
        }
        List<Response> responses = Concurrent.race(SESSIONS, session -> session % 2 == 0 ? batch(seller, productId, keys) : file(seller, productId, csv.toString()));
        int accepted = 0;
        for (Response response : responses) {
            assertEquals(200, response.status(), response.body());
            accepted += (Integer) response.json().get("accepted");
        }
        assertEquals(values, accepted);
        assertEquals(values, count(productId));
        assertEquals(0, duplicateValues(productId));
    }

    @Tag("TC-405")
    @Test
    void sessionsCannotPushThePoolPastTheLimitTogether() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        assertEquals(1, batch(seller, productId, "BASE-1").json().get("accepted"), "первая загрузка заводит ключ данных");
        fillPool(productId, 99_990);
        try {
            assertEquals(99_990, count(productId));

            // В пуле место ровно для двух загрузок по пять ключей: остальные четыре отклоняются целиком
            List<Response> responses = Concurrent.race(SESSIONS, session -> {
                String[] keys = new String[5];
                for (int k = 0; k < keys.length; k++) {
                    keys[k] = "LIM-" + session + "-" + k;
                }
                return batch(seller, productId, keys);
            });
            List<Integer> statuses = new ArrayList<>();
            for (Response response : responses) {
                statuses.add(response.status());
                if (response.status() == 409) {
                    assertEquals("pool-limit-exceeded", code(response), response.body());
                }
            }
            assertEquals(2, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
            assertEquals(SESSIONS - 2, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
            assertEquals(100_000, count(productId), "предел не превышен ни на один ключ");
            assertEquals(0, duplicateValues(productId));
        } finally {
            dropPool(productId);
        }
    }
}
