package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.testing.Concurrent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Два диспетчера одновременно (ADR-011, NFT-2.4): выдачи берутся в работу с арендой строки ({@code FOR UPDATE SKIP LOCKED}), поэтому одну
 * выдачу двое не берут. Доказательство это письма в почтовой заглушке: она не склеивает повторы (режим {@code dedupe = false}), и каждая
 * выдача должна дать ровно одно письмо и ровно одну попытку. Задание CI {@code concurrency} повторяет тесты с тегом {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class DeliveryDispatchConcurrencyIT extends DeliveryStandIT {

    /** Две пачки по 20: оба прохода получают полную пачку одновременно. */
    private static final int DELIVERIES = 44;

    @Tag("TC-043")
    @Test
    void twoDispatchersTakingTheQueueTogetherSendEveryDeliveryExactlyOnce() throws Exception {
        emailMode(Map.of("dedupe", false));
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < DELIVERIES; i++) {
            Placed p = seed(1);
            INVENTORY.issue(p.orderId(), 1);
            placed.add(p);
        }

        List<Integer> claimed = Concurrent.race(2, call -> dispatcher.runOnce());

        assertTrue(claimed.get(0) > 0 && claimed.get(1) > 0, "оба прохода должны были что-то взять: " + claimed);
        assertTrue(drive(Duration.ofSeconds(90), () -> placed.stream().allMatch(p -> "sent".equals(status(p)))), "не все выдачи отправлены");
        for (Placed p : placed) {
            assertEquals(1, mails(p).size(), "выдача " + p.orderId() + " дала не одно письмо");
            assertEquals(1, attemptNo(p), "попыток не одна");
            assertEquals(1, attempts(p).size());
            assertEquals(1, INVENTORY.calls(p.orderId()).size(), "ключи запрошены не один раз");
            assertEquals(1, events(p, "delivery.accepted").size());
        }
    }
}
