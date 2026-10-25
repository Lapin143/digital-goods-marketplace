package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * События выдачи на стенде (ADR-004, SM-06/T1, INV-18, INV-44, Р-170): настоящий потребитель Kafka сервиса читает {@code order.events} и
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


    private static long deliveriesOfKind(Placed p, String kind) {
        Long n = db.queryForObject("select count(*) from delivery.delivery where order_id = ? and kind = ?", Long.class, p.orderId(), kind);
        return n == null ? 0 : n;
    }

    /** Повторные выдачи заказа по порядку создания. */
    private static List<Map<String, Object>> repeats(Placed p) {
        return db.queryForList("select id, buyer_id, kind, address, product_title, quantity, attempt_no, status, (next_attempt_at is not null) as scheduled "
                + "from delivery.delivery where order_id = ? and kind = 'repeat' order by created_at, id", p.orderId());
    }

    private static String repeatStatus(Placed p, int index) {
        List<Map<String, Object>> rows = repeats(p);
        return rows.size() > index ? (String) rows.get(index).get("status") : null;
    }

    /** Заказ после смены адреса: тот же заказ и покупатель, адрес новый. */
    private static Placed movedTo(Placed p, String address) {
        return new Placed(p.orderId(), p.buyerId(), address, p.title(), p.quantity());
    }

    /** {@code order.address-updated} от order-service: поддержка сменила адрес выданного заказа. */
    static UUID publishAddressUpdated(Placed p, String address, UUID eventId) throws Exception {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", "email");
        delivery.put("address", address);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", p.orderId().toString());
        data.put("buyerId", p.buyerId().toString());
        data.put("ticketId", UUID.randomUUID().toString());
        data.put("delivery", delivery);
        return publish(orderEvents, "order-service", "order.events", "Order", "order.address-updated", eventId, p.orderId().toString(), data);
    }

    /** Заказ, письмо которого уже принято провайдером: то, что есть у заказа к моменту смены адреса. Возвращает ключи заказа. */
    private List<String> placeAndSend(Placed p) {
        List<String> keys = INVENTORY.issue(p.orderId(), p.quantity());
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))), "первичная выдача не отправлена");
        return keys;
    }

    private static String newAddress() {
        return "moved-" + UUID.randomUUID().toString().substring(0, 8) + "@mail.example";
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

    // ------------------------------------------------------------------------------------------------ TC-612: повторная выдача по смене адреса

    @Tag("TC-612")
    @Test
    void theAddressUpdatedEventCreatesTheQueuedRepeatDeliveryAndNeitherANewWatchNorAChangeOfThePrimaryDelivery() throws Exception {
        Placed p = place(2);
        placeAndSend(p);
        Map<String, Object> watchBefore = watchOf(p);
        String address = newAddress();

        UUID eventId = publishAddressUpdated(p, address, UUID.randomUUID());
        awaitProcessed(eventId);

        List<Map<String, Object>> repeats = repeats(p);
        assertEquals(1, repeats.size(), "повторная выдача создана");
        Map<String, Object> repeat = repeats.get(0);
        assertEquals("queued", repeat.get("status"));
        assertEquals(address, repeat.get("address"));
        assertEquals(p.title(), repeat.get("product_title"));
        assertEquals(2, ((Number) repeat.get("quantity")).intValue());
        assertEquals(p.buyerId(), repeat.get("buyer_id"));
        assertEquals(0, ((Number) repeat.get("attempt_no")).intValue());
        assertEquals(true, repeat.get("scheduled"), "первая попытка назначена");
        assertEquals("sent", status(p), "первичная выдача осталась как была");
        assertEquals(p.email(), deliveryOf(p).get("address"));
        assertEquals(1, watches(p), "контроль 30 минут один: повторная выдача его не открывает");
        assertEquals(watchBefore, watchOf(p), "срок и состояние контроля не сдвинуты");
        assertEquals(0, events(p, "delivery.overdue").size());
    }

    @Tag("TC-612")
    @Test
    void theSameEventTwiceAndAnotherEventWhileTheRepeatIsQueuedCreateNoSecondDelivery() throws Exception {
        Placed p = place(1);
        placeAndSend(p);
        String address = newAddress();
        UUID first = publishAddressUpdated(p, address, UUID.randomUUID());
        awaitProcessed(first);

        publishAddressUpdated(p, address, first);
        UUID other = publishAddressUpdated(p, newAddress(), UUID.randomUUID());
        awaitProcessed(other);

        List<Map<String, Object>> repeats = repeats(p);
        assertEquals(1, repeats.size(), "в очереди одна повторная выдача (Р-170)");
        assertEquals(address, repeats.get(0).get("address"), "адрес первого события не заменён вторым");
        assertEquals(2, deliveries(p));
        assertEquals(1L, db.queryForObject("select count(*) from delivery.delivery where order_id = ? and status = 'queued'", Long.class, p.orderId()),
                "выдача заказа в очереди одна (uq_delivery_order_id_queued)");
    }

    @Tag("TC-612")
    @Test
    void theEventOfAnOrderWithAQueuedPrimaryDeliveryAndOfAnOrderWithoutOneCreateNothing() throws Exception {
        Placed queued = place(1);
        Placed stranger = newOrder(1);

        UUID first = publishAddressUpdated(queued, newAddress(), UUID.randomUUID());
        UUID second = publishAddressUpdated(stranger, newAddress(), UUID.randomUUID());
        awaitProcessed(first);
        awaitProcessed(second);

        assertEquals(1, deliveries(queued), "пока первичная выдача стоит в очереди, второй выдачи нет");
        assertEquals(0, deliveriesOfKind(queued, "repeat"));
        assertEquals(0, deliveries(stranger), "у заказа без первичной выдачи повторять нечего");
        assertNull(watchOf(stranger));
    }

    // ------------------------------------------------------------------------------------------------ TC-613: письмо на новый адрес

    @Tag("TC-613")
    @Test
    void theRepeatLetterGoesToTheNewAddressWithTheSameKeysAndCarriesTheRepeatTypeInTheEvents() throws Exception {
        Placed p = place(2);
        List<String> keys = placeAndSend(p);
        assertSent(p, keys);
        String address = newAddress();
        Placed moved = movedTo(p, address);
        awaitProcessed(publishAddressUpdated(p, address, UUID.randomUUID()));

        assertTrue(drive(LIMIT, () -> "sent".equals(repeatStatus(p, 0))), "повторная выдача не отправлена");

        assertSent(moved, keys);
        assertEquals(1, mails(p).size(), "на прежний адрес ушло только первое письмо");
        assertEquals(2, INVENTORY.calls(p.orderId()).size(), "ключи запрошены у сервиса остатков по одному разу на письмо");
        List<Map<String, Object>> accepted = events(p, "delivery.accepted");
        assertEquals(2, accepted.size());
        assertEquals("primary", data(accepted.get(0)).get("deliveryType"));
        assertEquals("repeat", data(accepted.get(1)).get("deliveryType"));
        assertEquals(repeats(p).get(0).get("id").toString(), data(accepted.get(1)).get("deliveryId"));
        assertEquals(1, ((Number) data(accepted.get(1)).get("attemptNo")).intValue());
        assertEquals(1L, db.queryForObject("select count(*) from delivery.delivery_attempt where delivery_id = ?", Long.class, repeats(p).get(0).get("id")),
                "одна попытка, принятая провайдером");
        assertEquals(1, watches(p), "контроль 30 минут по-прежнему один");
        for (String key : keys) {
            assertEquals(0, occurrences(key), "значение ключа найдено в базе сервиса выдачи после повторного письма (INV-20, ST-01)");
        }
    }

    @Tag("TC-613")
    @Test
    void aRepeatThatIsAlreadySentDoesNotBlockTheNextChangeOfTheAddress() throws Exception {
        Placed p = place(1);
        List<String> keys = placeAndSend(p);
        String second = newAddress();
        awaitProcessed(publishAddressUpdated(p, second, UUID.randomUUID()));
        assertTrue(drive(LIMIT, () -> "sent".equals(repeatStatus(p, 0))));

        String third = newAddress();
        awaitProcessed(publishAddressUpdated(p, third, UUID.randomUUID()));
        assertTrue(drive(LIMIT, () -> "sent".equals(repeatStatus(p, 1))), "вторая повторная выдача не отправлена");

        assertEquals(3, deliveries(p));
        assertSent(movedTo(p, second), keys);
        assertSent(movedTo(p, third), keys);
        assertEquals(3, events(p, "delivery.accepted").size());
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
