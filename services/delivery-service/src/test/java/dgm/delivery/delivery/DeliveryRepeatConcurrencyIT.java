package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.service.DeliveryRepeat;
import dgm.delivery.delivery.service.DeliveryRepeat.Outcome;
import dgm.kit.testing.Concurrent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Одинаковые события {@code order.address-updated} обрабатываются одновременно (Р-170, ADR-011, SM-06/T1): повтор доставки Kafka и повторный щелчок
 * оператора с другим ключом дают два события об одном заказе. Первичная выдача заказа уже отправлена, поэтому очередь пуста и каждый участник гонки
 * пробует создать повторную выдачу: создаёт её один, остальные получают отказ уникального индекса {@code uq_delivery_order_id_queued}
 * (проверка «в очереди ли уже» до вставки гонку не закрывает, закрывает индекс). Задание CI {@code concurrency} повторяет тесты с тегом
 * {@code concurrency} 50 раз подряд.
 */
@Tag("concurrency")
class DeliveryRepeatConcurrencyIT extends DeliveryStandIT {

    /** Заказов за прогон: на каждый своя гонка, поэтому серия из 50 прогонов это 500 гонок. */
    private static final int ORDERS = 10;
    /** Участников одной гонки; соединений рабочей роли хватает с запасом (предел роли 8, сервис держит и свои). */
    private static final int EVENTS = 4;

    @Autowired
    DeliveryRepeat repeat;

    /** Заказ, первичная выдача которого уже отправлена: в очереди у заказа ничего нет. */
    private static Placed sentOrder() {
        Placed p = newOrder(1);
        UUID id = UUID.randomUUID();
        db.update("insert into delivery.delivery (id, order_id, buyer_id, kind, address, product_title, quantity, next_attempt_at) "
                + "values (?, ?, ?, 'primary', ?, ?, ?, now() + interval '1 day')", id, p.orderId(), p.buyerId(), p.email(), p.title(), p.quantity());
        db.update("update delivery.delivery set status = 'sent', attempt_no = 1, sent_at = now(), next_attempt_at = null where id = ?", id);
        return p;
    }

    @Tag("TC-045")
    @Test
    void identicalAddressEventsHandledTogetherCreateExactlyOneQueuedRepeatDeliveryPerOrder() {
        List<Placed> orders = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            orders.add(sentOrder());
        }
        try {
            for (Placed p : orders) {
                String address = "moved-" + p.orderId().toString().substring(0, 8) + "@mail.example";

                List<Outcome> outcomes = Concurrent.race(EVENTS, call -> repeat.accept(p.orderId(), address));

                assertEquals(1, outcomes.stream().filter(Outcome.CREATED::equals).count(), "создала повторную выдачу не одна обработка: " + outcomes);
                assertEquals(EVENTS - 1, outcomes.stream().filter(Outcome.ALREADY_QUEUED::equals).count(), "остальные пропущены: " + outcomes);
            }
            // доказательство отсутствия дублей это запрос к базе, а не только ответы участников гонки
            List<Map<String, Object>> duplicates = db.queryForList("select order_id, count(*) as queued from delivery.delivery where status = 'queued' "
                    + "group by order_id having count(*) > 1");
            assertTrue(duplicates.isEmpty(), "у заказов больше одной выдачи в очереди: " + duplicates);
            for (Placed p : orders) {
                assertEquals(1L, db.queryForObject("select count(*) from delivery.delivery where order_id = ? and kind = 'repeat'", Long.class, p.orderId()));
                assertEquals(1L, db.queryForObject("select count(*) from delivery.delivery where order_id = ? and status = 'queued'", Long.class, p.orderId()));
                assertEquals(0L, db.queryForObject("select count(*) from delivery.delivery_watch where order_id = ?", Long.class, p.orderId()),
                        "повторная выдача контроль 30 минут не открывает");
            }
        } finally {
            // Выдачи теста не должны попасть в проходы диспетчера других тестов (ключей у сервиса остатков для них нет)
            for (Placed p : orders) {
                db.update("delete from delivery.delivery where order_id = ?", p.orderId());
            }
        }
    }
}
