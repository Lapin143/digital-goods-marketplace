package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.Timestamps;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * События выдачи на стенде (ADR-004, SM-06/T1, INV-18, INV-44): настоящий потребитель Kafka сервиса читает {@code order.events} и
 * {@code identity.events}, события публикуют соседи от имени своих сертификатов (права Kafka выданы по CN). Результат проверяется по таблицам
 * выдачи, контроля и {@code processed_event}. Обработка идёт фоном, поэтому итог ждут опросом базы.
 */
class DeliveryEventsIT extends DeliveryStandIT {

    private static final Duration WINDOW = Duration.ofMinutes(30);

    private static void awaitProcessed(UUID eventId) {
        assertTrue(await(LIMIT, () -> processed(eventId)), "событие " + eventId + " не обработано потребителем " + DOMAIN);
    }

    private static Map<String, Object> watchOf(Placed p) {
        List<Map<String, Object>> rows = db.queryForList("select status, to_char(deadline_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') as deadline "
                + "from delivery.delivery_watch where order_id = ?", p.orderId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static long deliveries(Placed p) {
        Long n = db.queryForObject("select count(*) from delivery.delivery where order_id = ?", Long.class, p.orderId());
        return n == null ? 0 : n;
    }

    private static long watches(Placed p) {
        Long n = db.queryForObject("select count(*) from delivery.delivery_watch where order_id = ?", Long.class, p.orderId());
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------------------------------ TC-602: оплата заказа

    @Tag("TC-602")
    @Test
    void thePaidOrderEventCreatesTheQueuedPrimaryDeliveryWithTheSnapshotAndOpensTheWatchFromThePaymentTime() throws Exception {
        Placed p = newOrder(2);
        Instant paidAt = Instant.now().minusSeconds(90).truncatedTo(ChronoUnit.MILLIS);
        UUID eventId = publishPaid(p, paidAt, UUID.randomUUID());

        awaitProcessed(eventId);

        Map<String, Object> row = deliveryOf(p);
        assertNotNull(row, "первичная выдача не создана");
        assertEquals("queued", row.get("status"));
        assertEquals("primary", row.get("kind"));
        assertEquals("email", row.get("channel"));
        assertEquals(p.email(), row.get("address"));
        assertEquals(p.title(), row.get("product_title"));
        assertEquals(2, ((Number) row.get("quantity")).intValue());
        assertEquals(p.buyerId(), row.get("buyer_id"));
        assertEquals(0, ((Number) row.get("attempt_no")).intValue());
        assertEquals(true, row.get("scheduled"), "первая попытка назначена");
        Map<String, Object> watch = watchOf(p);
        assertNotNull(watch, "контроль доставки не открыт");
        assertEquals("open", watch.get("status"));
        assertEquals(Timestamps.format(paidAt.plus(WINDOW)), watch.get("deadline"), "срок контроля: 30 минут от оплаты, а не от прихода события");
    }

    @Tag("TC-602")
    @Test
    void theSameOrderUnderAnotherEventIdAndTheSameEventTwiceCreateNoSecondDeliveryOrWatch() throws Exception {
        Placed p = newOrder(1);
        Instant paidAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID first = publishPaid(p, paidAt, UUID.randomUUID());
        awaitProcessed(first);

        publishPaid(p, paidAt, first);
        UUID other = publishPaid(p, paidAt.plusSeconds(5), UUID.randomUUID());
        awaitProcessed(other);

        assertEquals(1, deliveries(p), "первичная выдача заказа одна");
        assertEquals(1, watches(p), "контроль один");
        assertEquals(Timestamps.format(paidAt.plus(WINDOW)), watchOf(p).get("deadline"), "контроль остался от первого события");
        assertEquals(p.email(), deliveryOf(p).get("address"));
    }

    // ------------------------------------------------------------------------------------------------ TC-605: обезличивание

    private static UUID anonymized(UUID user) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", user.toString());
        data.put("requestedBy", "user");
        return publish(identityEvents, "platform-service", "identity.events", "User", "user.anonymized", UUID.randomUUID(), user.toString(), data);
    }

    @Tag("TC-605")
    @Test
    void anonymizingAUserReplacesTheAddressInHisOpenDeliveriesOnlyAndTheLetterNeverGoesOut() throws Exception {
        UUID buyer = UUID.randomUUID();
        Placed queued = place(buyer, 1);
        Placed sent = place(buyer, 1);
        Placed stranger = place(UUID.randomUUID(), 1);
        INVENTORY.issue(sent.orderId(), 1);
        assertTrue(drive(LIMIT, () -> "sent".equals(status(sent))));

        UUID eventId = anonymized(buyer);
        awaitProcessed(eventId);
        // проходы диспетчера, которые довели «отправлено», могли успеть взять и выдачу «в очереди» до обезличивания (ключей у неё ещё нет): считаем с этого места
        int keyCallsBefore = INVENTORY.calls(queued.orderId()).size();

        assertEquals("anonymized@anonymized.invalid", deliveryOf(queued).get("address"));
        assertEquals("anonymized@anonymized.invalid", deliveryOf(sent).get("address"), "отправленная выдача открыта, адрес в ней тоже заменяется");
        assertEquals(stranger.email(), deliveryOf(stranger).get("address"), "чужие выдачи не затронуты");
        assertEquals("queued", status(queued));

        assertTrue(drive(LIMIT, () -> "failed".equals(status(queued))));
        assertEquals("address_anonymized", deliveryOf(queued).get("last_error_code"));
        assertEquals(0, stubs.emails("anonymized@anonymized.invalid", null, null).size());
        assertEquals(0, mails(queued).size());
        assertEquals(keyCallsBefore, INVENTORY.calls(queued.orderId()).size(), "после обезличивания ключи по выдаче не запрашиваются");

        UUID again = anonymized(buyer);
        awaitProcessed(again);
        assertEquals("anonymized@anonymized.invalid", deliveryOf(sent).get("address"));
    }
}
