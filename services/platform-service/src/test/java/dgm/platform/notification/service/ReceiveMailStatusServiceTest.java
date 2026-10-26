package dgm.platform.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.domain.MailStatus;
import dgm.platform.notification.domain.MailStatusNotice;
import dgm.platform.notification.domain.RetryPolicy;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.service.ReceiveMailStatusService.Result;
import dgm.platform.notification.testing.FakeContacts;
import dgm.platform.notification.testing.FakeNotificationTransaction;
import dgm.platform.notification.testing.InMemoryNotifications;
import dgm.platform.notification.testing.RecordingEmail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Статусы писем платформы от провайдера (Р-171, F4-6): {@code delivered} переводит {@code sent} в {@code delivered}, отказ провайдера один раз
 * переводит письмо в {@code failed} и публикует {@code notification.failed}, повтор, чужое письмо и неизвестный статус ничего не меняют.
 */
@Tag("TC-718")
class ReceiveMailStatusServiceTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final Instant START = Instant.parse("2026-10-26T12:00:00Z");
    private static final String ADDRESS = "anna@mail.example";

    private ManualClock clock;
    private InMemoryNotifications queue;
    private RecordingEmail email;
    private FakeNotificationTransaction tx;
    private SimpleMeterRegistry metrics;
    private NotificationService notifications;
    private NotificationDispatcher dispatcher;
    private ReceiveMailStatusService statuses;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(START);
        queue = new InMemoryNotifications();
        FakeContacts contacts = new FakeContacts();
        contacts.put(USER, ADDRESS, "Анна Смирнова");
        email = new RecordingEmail();
        tx = new FakeNotificationTransaction();
        metrics = new SimpleMeterRegistry();
        Letters letters = new Letters(ZoneId.of("Europe/Moscow"), "МСК");
        notifications = new NotificationService(queue, letters, clock);
        dispatcher = new NotificationDispatcher(queue, contacts, letters, email, tx, RetryPolicy.standard(), Duration.ofSeconds(30), 10, clock, metrics);
        statuses = new ReceiveMailStatusService(queue, tx, clock, metrics);
    }

    /** Письмо в очереди, ещё не отправленное. */
    private UUID queued() {
        notifications.enqueue(new NotificationRequest(USER, "seller-returned", Map.of("comment", "Приложите выписку"), UUID.randomUUID()));
        return queue.all().get(queue.all().size() - 1).source.id();
    }

    /** Письмо, которое провайдер принял: статус {@code sent}. */
    private UUID sent() {
        UUID id = queued();
        dispatcher.dispatchDue();
        assertEquals("sent", queue.row(id).status);
        return id;
    }

    private MailStatusNotice notice(UUID id, MailStatus status, String reason) {
        return new MailStatusNotice(id.toString(), status, clock.instant(), reason);
    }

    private double counted(String status, String result) {
        var counter = metrics.find("notification_provider_statuses_total").tag("status", status).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double letters(String result) {
        return metrics.find("notifications_total").tag("result", result).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void deliveredMovesTheSentLetterToDeliveredAndPublishesNothing() {
        UUID id = sent();

        assertEquals(Result.APPLIED, statuses.apply(notice(id, MailStatus.DELIVERED, null)));

        assertEquals("delivered", queue.row(id).status);
        assertEquals(java.util.Set.of("delivered"), queue.providerStatuses(id));
        assertTrue(tx.events().isEmpty());
        assertEquals(1.0, counted("delivered", "applied"));
        assertEquals(1.0, letters("delivered"));
    }

    @Test
    void theSameStatusTwiceChangesNothingTheSecondTime() {
        UUID id = sent();
        statuses.apply(notice(id, MailStatus.DELIVERED, null));

        assertEquals(Result.DUPLICATE, statuses.apply(notice(id, MailStatus.DELIVERED, null)));

        assertEquals("delivered", queue.row(id).status);
        assertEquals(1.0, letters("delivered"), "доставка посчитана один раз");
        assertEquals(1.0, counted("delivered", "duplicate"));
    }

    @Test
    void acceptedIsOnlyRecordedAndTheLetterStaysSent() {
        UUID id = sent();

        assertEquals(Result.RECORDED, statuses.apply(notice(id, MailStatus.ACCEPTED, null)));

        assertEquals("sent", queue.row(id).status);
        assertEquals(java.util.Set.of("accepted"), queue.providerStatuses(id));
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void providerFailureMovesTheLetterToFailedWithTheDeadQueueFlagAndPublishesOneEvent() {
        UUID id = sent();

        assertEquals(Result.APPLIED, statuses.apply(notice(id, MailStatus.FAILED, "address_rejected")));

        InMemoryNotifications.Row row = queue.row(id);
        assertEquals("failed", row.status);
        assertTrue(row.inDeadQueue);
        assertEquals("provider_address_rejected", row.lastErrorCode);
        assertNull(row.nextAttemptAt);
        assertEquals(1, tx.events().size());
        FakeNotificationTransaction.Event event = tx.events().get(0);
        assertEquals("notification.events", event.topic());
        assertEquals("Notification", event.aggregateType());
        assertEquals(id.toString(), event.aggregateId());
        assertEquals("notification.failed", event.type());
        assertEquals(1, event.schemaVersion());
        assertEquals(Map.of("notificationId", id.toString(), "channel", "email", "template", "seller-returned", "recipientUserId", USER.toString(),
                "attempts", 1, "reason", "address_rejected"), event.data());
        assertEquals(1.0, letters("failed"));
        assertEquals(1.0, counted("failed", "applied"));
    }

    @Test
    void anyOtherReasonOrNoReasonIsReportedAsExhaustedAttempts() {
        UUID other = sent();
        UUID none = sent();

        statuses.apply(notice(other, MailStatus.FAILED, "other"));
        statuses.apply(notice(none, MailStatus.FAILED, null));

        assertEquals(2, tx.events().size());
        for (FakeNotificationTransaction.Event event : tx.events()) {
            assertEquals("attempts_exhausted", event.data().get("reason"));
        }
        assertEquals("provider_failed", queue.row(other).lastErrorCode);
        assertEquals("provider_failed", queue.row(none).lastErrorCode);
    }

    @Test
    void theSameFailureWebhookTwiceGivesTheEventOnce() {
        UUID id = sent();

        assertEquals(Result.APPLIED, statuses.apply(notice(id, MailStatus.FAILED, "address_rejected")));
        assertEquals(Result.DUPLICATE, statuses.apply(notice(id, MailStatus.FAILED, "address_rejected")));
        assertEquals(Result.DUPLICATE, statuses.apply(new MailStatusNotice(id.toString(), MailStatus.FAILED, clock.instant().plusSeconds(60), "other")));

        assertEquals(1, tx.events().size());
        assertEquals(1.0, letters("failed"));
    }

    @Test
    void failureAfterDeliveryChangesNothingBecauseConfirmedDeliveryIsNotRevoked() {
        UUID id = sent();
        statuses.apply(notice(id, MailStatus.DELIVERED, null));

        assertEquals(Result.RECORDED, statuses.apply(notice(id, MailStatus.FAILED, "other")));

        assertEquals("delivered", queue.row(id).status);
        assertFalse(queue.row(id).inDeadQueue);
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void providerFailureAfterThePlatformsOwnFailureGivesNoSecondEvent() {
        email.failNext(4, "provider_unavailable", false);
        UUID id = queued();
        for (int i = 0; i < 4; i++) {
            dispatcher.dispatchDue();
            clock.advance(Duration.ofSeconds(30));
        }
        assertEquals("failed", queue.row(id).status);
        assertEquals(1, tx.events().size(), "событие о собственном исходе платформы");

        assertEquals(Result.RECORDED, statuses.apply(notice(id, MailStatus.FAILED, "other")));

        assertEquals(1, tx.events().size(), "условное обновление не находит строку, второго события нет");
    }

    @Test
    void deliveredAfterThePlatformsOwnFailureDoesNotReviveTheLetter() {
        email.failNext(1, "address_rejected", true);
        UUID id = queued();
        dispatcher.dispatchDue();
        assertEquals("failed", queue.row(id).status);

        assertEquals(Result.RECORDED, statuses.apply(notice(id, MailStatus.DELIVERED, null)));

        assertEquals("failed", queue.row(id).status);
        assertEquals(1, tx.events().size());
    }

    @Test
    void deliveredThatOutrunsTheRecordOfTheSendIsAppliedWhenTheSendIsRecorded() {
        UUID id = queued();

        assertEquals(Result.RECORDED, statuses.apply(notice(id, MailStatus.DELIVERED, null)));
        assertEquals("queued", queue.row(id).status, "письмо ещё в очереди, переход queued в delivered запрещён");

        dispatcher.dispatchDue();

        assertEquals("delivered", queue.row(id).status, "запись исхода отправки подхватила уже пришедшую доставку");
        assertEquals(1, queue.all().size());
        assertEquals(1, email.accepted().size());
    }

    @Test
    void failureThatOutrunsTheRecordOfTheSendFailsTheQueuedLetterAndItIsNeverSentAgain() {
        UUID id = queued();

        assertEquals(Result.APPLIED, statuses.apply(notice(id, MailStatus.FAILED, "address_rejected")));

        InMemoryNotifications.Row row = queue.row(id);
        assertEquals("failed", row.status);
        assertEquals(1, row.attempts, "число попыток в событии не меньше одной");
        assertEquals(1, tx.events().size());
        assertEquals(0, dispatcher.dispatchDue(), "недоставленное письмо из очереди не берётся");
        assertTrue(email.attempts().isEmpty());
    }

    @Test
    void messageThatIsNotOursOrNotKnownChangesNothingAndLooksLikeAnyOtherAnswer() {
        UUID id = sent();

        assertEquals(Result.UNKNOWN, statuses.apply(new MailStatusNotice("msg_44a1b9", MailStatus.FAILED, clock.instant(), "other")));
        assertEquals(Result.UNKNOWN, statuses.apply(notice(UUID.randomUUID(), MailStatus.FAILED, "other")));

        assertEquals("sent", queue.row(id).status);
        assertTrue(queue.providerStatuses(id).isEmpty());
        assertTrue(tx.events().isEmpty());
        assertEquals(2.0, counted("failed", "unknown"));
    }

    @Test
    void unknownStatusIsIgnoredAndNotRecorded() {
        UUID id = sent();

        assertEquals(Result.IGNORED, statuses.apply(notice(id, MailStatus.OTHER, null)));

        assertEquals("sent", queue.row(id).status);
        assertTrue(queue.providerStatuses(id).isEmpty());
    }

    @Test
    void eventDataNeverHoldsTheAddressTheNameOrTheTextOfTheLetter() {
        UUID id = sent();
        statuses.apply(notice(id, MailStatus.FAILED, "address_rejected"));

        String data = tx.events().get(0).data().toString();

        assertFalse(data.contains("@"), data);
        assertFalse(data.contains("Смирнова"), data);
        assertFalse(data.contains("Приложите"), data);
    }
}
