package dgm.delivery.delivery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.domain.StatusNotice;
import dgm.delivery.delivery.service.DeliveryStatuses.Result;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Применение статуса письма от провайдера (SM-06/T5 и T6, ADR-011, TC-607): «доставлено» и «не доставлено» меняют выдачу «отправлено» один раз и
 * пишут одно событие; повтор, неизвестное письмо, статус раньше записи отправки, статус, не вписывающийся в состояние, и перестановка статусов не
 * меняют ничего лишнего (TC-033).
 */
@Tag("TC-607")
class DeliveryStatusesTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID ID = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");

    private FakeDeliveries deliveries;
    private FakeTransaction transaction;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private DeliveryStatuses statuses;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        transaction = new FakeTransaction();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        statuses = new DeliveryStatuses(deliveries, transaction, DeliverySettings.defaults(), clock, meters);
        // оплата за минуту до отправки, контроль открыт на 30 минут от оплаты
        deliveries.addSent(ID, ORDER, BUYER, "anna@mail.example", "Игра", 2, 2, T0.minusSeconds(5));
        deliveries.insertWatch(ORDER, T0.minusSeconds(60).plus(Duration.ofMinutes(30)), T0.minusSeconds(60));
    }

    private static StatusNotice notice(ProviderStatus status, Instant at, String reason) {
        return new StatusNotice(ID.toString(), status, at, reason);
    }

    private static StatusNotice notice(ProviderStatus status) {
        return notice(status, T0.plusSeconds(3), null);
    }

    private double count(String status, String result) {
        return meters.counter("delivery_provider_statuses_total", "status", status, "result", result).count();
    }

    @Test
    void aDeliveredStatusMakesTheSentDeliveryDeliveredAtTheTimeOfTheProviderAndPublishesOneEvent() {
        assertEquals(Result.APPLIED, statuses.apply(notice(ProviderStatus.DELIVERED, T0.plusSeconds(3), null)));

        FakeDeliveries.Row row = deliveries.row(ID);
        assertEquals("delivered", row.status);
        assertEquals(T0.plusSeconds(3), row.deliveredAt, "время доставки по данным провайдера");
        assertEquals("closed", deliveries.watchStatus(ORDER), "доставка закрывает контроль 30 минут");
        List<FakeTransaction.Published> events = transaction.published("delivery.delivered");
        assertEquals(1, events.size());
        FakeTransaction.Published event = events.get(0);
        assertEquals("delivery.events", event.topic());
        assertEquals("Delivery", event.aggregateType());
        assertEquals(ORDER.toString(), event.aggregateId(), "ключ сообщения это заказ");
        assertEquals(Map.of("deliveryId", ID.toString(), "orderId", ORDER.toString(), "deliveryType", "primary", "deliveredAt", "2026-10-19T12:00:03.000Z"),
                event.data());
        assertEquals(1, transaction.published().size(), "других событий нет");
        assertEquals(1, count("delivered", "applied"));
        assertEquals(1, meters.timer("delivery_time_seconds", "stage", "delivered").count(), "время от оплаты до доставки записано");
    }

    @Tag("TC-613")
    @Test
    void aDeliveredRepeatCarriesTheRepeatTypeAndDoesNotEnterTheTimeFromThePayment() {
        UUID repeat = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
        deliveries.addSentRepeat(repeat, ORDER, BUYER, "new@mail.example", "Игра", 2, 1, T0.minusSeconds(2));

        assertEquals(Result.APPLIED, statuses.apply(new StatusNotice(repeat.toString(), ProviderStatus.DELIVERED, T0.plusSeconds(3), null)));

        assertEquals("delivered", deliveries.row(repeat).status);
        assertEquals("sent", deliveries.row(ID).status, "первичная выдача заказа не затронута");
        List<FakeTransaction.Published> events = transaction.published("delivery.delivered");
        assertEquals(1, events.size());
        assertEquals(repeat.toString(), events.get(0).data().get("deliveryId"));
        assertEquals("repeat", events.get(0).data().get("deliveryType"));
        assertEquals(0, meters.timer("delivery_time_seconds", "stage", "delivered").count(),
                "время от оплаты считается по первичной выдаче: повторная через дни испортила бы порог NFT-2.0");
    }

    @Test
    void aFailedStatusMakesTheSentDeliveryFailedWithTheAddressReasonAndPublishesOneEvent() {
        assertEquals(Result.APPLIED, statuses.apply(notice(ProviderStatus.FAILED, T0.plusSeconds(3), "address_rejected")));

        FakeDeliveries.Row row = deliveries.row(ID);
        assertEquals("failed", row.status);
        assertEquals("address_rejected", row.failReason);
        assertEquals("provider_address_rejected", row.lastErrorCode);
        assertEquals("open", deliveries.watchStatus(ORDER), "недоставка контроль не закрывает: срок 30 минут остаётся");
        List<FakeTransaction.Published> events = transaction.published("delivery.failed");
        assertEquals(1, events.size());
        assertEquals(Map.of("deliveryId", ID.toString(), "orderId", ORDER.toString(), "deliveryType", "primary", "reason", "address_rejected", "attempts", 2),
                events.get(0).data());
        assertEquals(1, transaction.published().size());
        assertEquals(1, meters.counter("delivery_failed_total", "reason", "address_rejected").count());
    }

    @Test
    void anyOtherRefusalOfTheProviderIsRecordedAsAnAddressRefusalWithItsOwnCode() {
        assertEquals(Result.APPLIED, statuses.apply(notice(ProviderStatus.FAILED, T0.plusSeconds(3), "other")));

        assertEquals("failed", deliveries.row(ID).status);
        assertEquals("address_rejected", deliveries.row(ID).failReason, "таблица знает две причины (Р-152)");
        assertEquals("provider_other", deliveries.row(ID).lastErrorCode);

        UUID second = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
        deliveries.addSent(second, UUID.fromString("0199e0a0-0000-7000-8000-000000000302"), BUYER, "anna@mail.example", "Игра", 1, 1, T0.minusSeconds(5));
        assertEquals(Result.APPLIED, statuses.apply(new StatusNotice(second.toString(), ProviderStatus.FAILED, T0, null)));
        assertEquals("provider_other", deliveries.row(second).lastErrorCode, "причины нет: код по умолчанию");
    }

    @Test
    void theSameStatusTwiceChangesNothingTheSecondTime() {
        assertEquals(Result.APPLIED, statuses.apply(notice(ProviderStatus.DELIVERED)));
        assertEquals(Result.DUPLICATE, statuses.apply(notice(ProviderStatus.DELIVERED, T0.plusSeconds(9), null)));
        assertEquals(Result.DUPLICATE, statuses.apply(notice(ProviderStatus.DELIVERED, T0.plusSeconds(30), null)));

        assertEquals(1, transaction.published("delivery.delivered").size());
        assertEquals(T0.plusSeconds(3), deliveries.row(ID).deliveredAt, "время доставки не перезаписывается");
        assertEquals(1, count("delivered", "applied"));
        assertEquals(2, count("delivered", "duplicate"));
    }

    @Test
    void aMessageThatIsNotOursOrNotKnownIsAnsweredWithoutChanges() {
        UUID stranger = UUID.fromString("0199e0a0-0000-7000-8000-0000000009ff");

        assertEquals(Result.UNKNOWN, statuses.apply(new StatusNotice(stranger.toString(), ProviderStatus.DELIVERED, T0, null)));
        assertEquals(Result.UNKNOWN, statuses.apply(new StatusNotice("msg_91c3d7", ProviderStatus.DELIVERED, T0, null)));
        assertEquals(Result.UNKNOWN, statuses.apply(new StatusNotice(ID.toString().toUpperCase(java.util.Locale.ROOT), ProviderStatus.DELIVERED, T0, null)));

        assertEquals("sent", deliveries.row(ID).status);
        assertEquals(0, transaction.published().size());
        assertTrue(deliveries.providerStatuses().isEmpty());
        assertEquals(3, count("delivered", "unknown"));
    }

    @Test
    void aStatusThatOvertookTheRecordOfTheSendIsNotRecordedAndIsAppliedWhenItComesAgain() {
        UUID queued = UUID.fromString("0199e0a0-0000-7000-8000-000000000103");
        UUID order = UUID.fromString("0199e0a0-0000-7000-8000-000000000303");
        deliveries.insertPrimary(queued, order, BUYER, "anna@mail.example", "Игра", 1, T0);

        assertEquals(Result.PREMATURE, statuses.apply(new StatusNotice(queued.toString(), ProviderStatus.DELIVERED, T0, null)));

        assertEquals("queued", deliveries.row(queued).status);
        assertTrue(deliveries.providerStatuses().isEmpty(), "статус не записан, поэтому опрос применит его позже");
        assertEquals(0, transaction.published().size());

        deliveries.claimDue(T0, Duration.ofMinutes(2), 10);
        deliveries.markSent(queued, 0, T0, T0);
        assertEquals(Result.APPLIED, statuses.apply(new StatusNotice(queued.toString(), ProviderStatus.DELIVERED, T0.plusSeconds(1), null)));
        assertEquals("delivered", deliveries.row(queued).status);
    }

    @Test
    void anAcceptedStatusIsOnlyRecordedAndAnUnknownStatusIsIgnored() {
        assertEquals(Result.RECORDED, statuses.apply(notice(ProviderStatus.ACCEPTED)));
        assertEquals(Result.DUPLICATE, statuses.apply(notice(ProviderStatus.ACCEPTED)));
        assertEquals(Result.IGNORED, statuses.apply(notice(ProviderStatus.OTHER)));

        assertEquals("sent", deliveries.row(ID).status);
        assertEquals(0, transaction.published().size());
        assertEquals(1, deliveries.providerStatuses().size(), "записан один статус accepted");
        assertEquals("open", deliveries.watchStatus(ORDER));
    }

    @Test
    @Tag("TC-033")
    void aStatusThatContradictsTheStateIsNotAppliedAndAConfirmedDeliveryIsNeverRevoked() {
        assertEquals(Result.APPLIED, statuses.apply(notice(ProviderStatus.DELIVERED)));

        assertEquals(Result.CONFLICT, statuses.apply(notice(ProviderStatus.FAILED, T0.plusSeconds(8), "address_rejected")));

        assertEquals("delivered", deliveries.row(ID).status, "SM-06: доставка не отзывается");
        assertNull(deliveries.row(ID).failReason);
        assertEquals(0, transaction.published("delivery.failed").size());
        assertEquals(1, count("failed", "conflict"));

        UUID failed = UUID.fromString("0199e0a0-0000-7000-8000-000000000104");
        deliveries.addSent(failed, UUID.fromString("0199e0a0-0000-7000-8000-000000000304"), BUYER, "anna@mail.example", "Игра", 1, 1, T0.minusSeconds(5));
        assertEquals(Result.APPLIED, statuses.apply(new StatusNotice(failed.toString(), ProviderStatus.FAILED, T0, "other")));
        assertEquals(Result.CONFLICT, statuses.apply(new StatusNotice(failed.toString(), ProviderStatus.DELIVERED, T0.plusSeconds(1), null)));
        assertEquals("failed", deliveries.row(failed).status, "ошибка конечный статус выдачи");
        assertEquals(1, transaction.published("delivery.delivered").size(), "событие доставки только по первой выдаче");
    }

    @Test
    @Tag("TC-033")
    void theOrderOfTheStatusesAndTheirRepeatsGiveOneOutcome() {
        List<List<ProviderStatus>> orders = List.of(
                List.of(ProviderStatus.ACCEPTED, ProviderStatus.DELIVERED, ProviderStatus.DELIVERED),
                List.of(ProviderStatus.DELIVERED, ProviderStatus.ACCEPTED, ProviderStatus.DELIVERED, ProviderStatus.ACCEPTED),
                List.of(ProviderStatus.DELIVERED, ProviderStatus.DELIVERED, ProviderStatus.ACCEPTED));
        int number = 0;
        for (List<ProviderStatus> order : orders) {
            number++;
            UUID id = UUID.fromString("0199e0a0-0000-7000-8000-00000000020" + number);
            UUID orderId = UUID.fromString("0199e0a0-0000-7000-8000-00000000030" + (number + 4));
            deliveries.addSent(id, orderId, BUYER, "anna@mail.example", "Игра", 1, 1, T0.minusSeconds(5));
            deliveries.insertWatch(orderId, T0.plus(Duration.ofMinutes(29)), T0);
            for (ProviderStatus status : order) {
                statuses.apply(new StatusNotice(id.toString(), status, T0.plusSeconds(2), null));
            }
            assertEquals("delivered", deliveries.row(id).status, "порядок " + order);
            assertEquals("closed", deliveries.watchStatus(orderId), "порядок " + order);
        }
        assertEquals(3, transaction.published("delivery.delivered").size(), "по одному событию на выдачу при любом порядке и повторе");
    }

    @Test
    void aLateDeliveryAfterTheHandOverClosesTheWatchAndPublishesTheDeliveredEventForTheTicket() {
        assertTrue(deliveries.handOver(ORDER, T0), "контроль передан в поддержку (E11)");

        assertEquals(Result.APPLIED, statuses.apply(notice(ProviderStatus.DELIVERED)));

        assertEquals("delivered", deliveries.row(ID).status);
        assertEquals("closed", deliveries.watchStatus(ORDER), "handed_over → closed");
        assertEquals(1, transaction.published("delivery.delivered").size(), "это же событие закрывает обращение системы (SM-06, решение 4)");
    }

    @Test
    void aDeliveredTimeBeforeThePaymentIsRecordedAsZeroDuration() {
        assertEquals(Result.APPLIED, statuses.apply(notice(ProviderStatus.DELIVERED, T0.minus(Duration.ofHours(2)), null)));

        assertEquals(1, meters.timer("delivery_time_seconds", "stage", "delivered").count(), "расхождение часов провайдера метрику не ломает");
    }
}
