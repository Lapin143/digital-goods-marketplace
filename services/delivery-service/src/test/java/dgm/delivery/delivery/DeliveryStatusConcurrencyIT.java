package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Concurrent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Гонки статусов писем и контроля 30 минут (ADR-011, SM-06, NFT-2.4). Доказательство это база: один переход и одно событие на выдачу, одна
 * запись о статусе, одно событие {@code delivery.overdue} на заказ, а не только ответы потоков. Задание CI {@code concurrency} повторяет
 * тесты с тегом {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class DeliveryStatusConcurrencyIT extends DeliveryStatusStandIT {

    /** Контролей больше порции прохода (50): ни один проход не заберёт все, оба должны что-то взять. */
    private static final int WATCHES = 60;

    // ------------------------------------------------------------------------------------------------ TC-033: статус письма

    @Tag("TC-033")
    @Test
    void theSameStatusFromManyThreadsIsAppliedOnce() throws Exception {
        Placed p = seedSent(Duration.ofMinutes(1), Instant.now().plus(Duration.ofMinutes(29)));
        UUID id = idOf(p);
        String body = Json.write(notice(id, "delivered"));

        List<Response> responses = Concurrent.race(8, call -> send(body));

        for (Response response : responses) {
            assertOk(response);
        }
        assertEquals("delivered", status(p));
        assertEquals(List.of("delivered"), providerStatuses(id), "одна запись о статусе");
        assertEquals(1, events(p, "delivery.delivered").size(), "одно событие, сколько бы потоков ни прислало статус");
        assertEquals("closed", watchStatus(p));
    }

    @Tag("TC-033")
    @Test
    void aDeliveredAndAFailedNotificationRaceAndExactlyOneOfThemWins() throws Exception {
        for (int round = 0; round < 8; round++) {
            Placed p = seedSent(Duration.ofMinutes(1), Instant.now().plus(Duration.ofMinutes(29)));
            UUID id = idOf(p);

            List<Response> responses = Concurrent.race(2, call -> call == 0 ? send(notice(id, "delivered"))
                    : send(notice(id, "failed", Instant.now(), "address_rejected")));

            for (Response response : responses) {
                assertOk(response);
            }
            String finalStatus = status(p);
            assertTrue("delivered".equals(finalStatus) || "failed".equals(finalStatus), "итог гонки: " + finalStatus + ", раунд " + round);
            assertEquals(List.of(finalStatus), providerStatuses(id), "записан только победивший статус, раунд " + round);
            assertEquals(1, events(p, "delivery." + finalStatus).size(), "событие победителя, раунд " + round);
            assertEquals(1, events(p, "delivery.delivered").size() + events(p, "delivery.failed").size(), "событий итога ровно одно, раунд " + round);
        }
    }

    @Tag("TC-033")
    @Test
    void aWebhookAndThePollerRaceOnTheSameDeliveryAndItIsClosedOnce() throws Exception {
        for (int round = 0; round < 4; round++) {
            Placed p = seedSent(Duration.ofMinutes(10), Instant.now().plus(Duration.ofMinutes(20)));
            UUID id = idOf(p);
            providerSays(id, "delivered", null);

            Concurrent.race(2, call -> {
                if (call == 0) {
                    return send(notice(id, "delivered")).status();
                }
                // опрос идёт по кругу порциями: несколько проходов, чтобы дойти до выдачи, пока вебхук ещё в пути (базу трогает только главный поток)
                int closed = 0;
                for (int pass = 0; pass < 10; pass++) {
                    closed += poller.runOnce();
                }
                return closed;
            });

            assertTrue(poll(Duration.ofSeconds(60), () -> "delivered".equals(status(p))), "раунд " + round);
            assertEquals(List.of("delivered"), providerStatuses(id), "раунд " + round);
            assertEquals(1, events(p, "delivery.delivered").size(), "раунд " + round);
        }
    }

    // ------------------------------------------------------------------------------------------------ TC-044: контроль 30 минут

    @Tag("TC-044")
    @Test
    void twoWatchPassesTakingTheDueWatchesTogetherHandOverEveryOrderExactlyOnce() throws Exception {
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < WATCHES; i++) {
            // срок два года назад: такие контроли берутся раньше остальных просроченных, что остались на стенде от прошлых прогонов
            placed.add(seedSent(Duration.ofMinutes(40), Instant.now().minus(Duration.ofDays(730)).minusSeconds(i)));
        }

        List<Integer> handed = Concurrent.race(2, call -> watch.runOnce());

        assertTrue(handed.get(0) > 0 && handed.get(1) > 0, "оба прохода должны были что-то взять: " + handed);
        assertTrue(keepWatch(Duration.ofSeconds(90), () -> placed.stream().allMatch(p -> "handed_over".equals(watchStatus(p)))), "не все контроли переданы");
        for (Placed p : placed) {
            assertEquals(1, events(p, "delivery.overdue").size(), "заказ " + p.orderId() + ": событие не одно");
            assertEquals("sent", status(p));
        }
    }
}
