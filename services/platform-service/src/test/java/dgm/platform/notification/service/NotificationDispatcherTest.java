package dgm.platform.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.domain.RetryPolicy;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.testing.FakeContacts;
import dgm.platform.notification.testing.FakeNotificationTransaction;
import dgm.platform.notification.testing.InMemoryNotifications;
import dgm.platform.notification.testing.RecordingEmail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Отправитель уведомлений: адрес берётся у identity в момент отправки, повторы 1, 5, 25 секунд, {@code notification.failed} после
 * исчерпания попыток и при отказе провайдера, аренда письма на время отправки (шаг 8 Ф4, решения Р-46 – Р-49).
 */
@Tag("TC-220")
class NotificationDispatcherTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final Instant START = Instant.parse("2026-10-11T12:00:00Z");
    private static final String ADDRESS = "anna@mail.example";

    private ManualClock clock;
    private InMemoryNotifications queue;
    private FakeContacts contacts;
    private RecordingEmail email;
    private FakeNotificationTransaction tx;
    private SimpleMeterRegistry metrics;
    private NotificationService service;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(START);
        queue = new InMemoryNotifications();
        contacts = new FakeContacts();
        contacts.put(USER, ADDRESS, "Анна Смирнова");
        email = new RecordingEmail();
        tx = new FakeNotificationTransaction();
        metrics = new SimpleMeterRegistry();
        Letters letters = new Letters(ZoneId.of("Europe/Moscow"), "МСК");
        service = new NotificationService(queue, letters, clock);
        dispatcher = new NotificationDispatcher(queue, contacts, letters, email, tx, RetryPolicy.standard(), Duration.ofSeconds(30), 10, clock,
                metrics);
    }

    private UUID enqueueReturned() {
        service.enqueue(new NotificationRequest(USER, "seller-returned", Map.of("comment", "Приложите выписку"), UUID.randomUUID()));
        return queue.all().get(queue.all().size() - 1).source.id();
    }

    private double count(String result) {
        return metrics.find("notifications_total").tag("result", result).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void dueLetterIsSentToTheCurrentAddressWithTheNotificationIdAsMessageId() {
        UUID id = enqueueReturned();

        assertEquals(1, dispatcher.dispatchDue());

        assertEquals(1, email.accepted().size());
        OutgoingEmail mail = email.accepted().get(0);
        assertEquals(id.toString(), mail.messageId());
        assertEquals(ADDRESS, mail.to());
        assertEquals("Заявка продавца возвращена на доработку", mail.subject());
        assertTrue(mail.text().startsWith("Здравствуйте, Анна Смирнова!"), mail.text());
        assertTrue(mail.text().contains("Приложите выписку"), mail.text());
        InMemoryNotifications.Row row = queue.row(id);
        assertEquals("sent", row.status);
        assertEquals(1, row.attempts);
        assertEquals(START, row.sentAt);
        assertNull(row.nextAttemptAt);
        assertNull(row.lastErrorCode);
        assertFalse(row.inDeadQueue);
        assertEquals(0, tx.events().size());
        assertEquals(1.0, count("sent"));
    }

    @Test
    void theQueueNeverHoldsTheAddressOrTheName() {
        UUID id = enqueueReturned();
        dispatcher.dispatchDue();

        String stored = queue.row(id).source.params().toString() + queue.row(id).source.template() + queue.row(id).lastErrorCode;
        assertFalse(stored.contains("anna@mail.example"), stored);
        assertFalse(stored.contains("Смирнова"), stored);
    }

    @Test
    void addressChangedAfterQueueingIsPickedUpAtSendTime() {
        enqueueReturned();
        contacts.put(USER, "anna.new@mail.example", "Анна Смирнова");

        dispatcher.dispatchDue();

        assertEquals("anna.new@mail.example", email.accepted().get(0).to());
    }

    @Test
    void letterThatIsNotDueYetIsLeftAlone() {
        enqueueReturned();
        clock.set(START.minusSeconds(1));

        assertEquals(0, dispatcher.dispatchDue());
        assertTrue(email.attempts().isEmpty());
    }

    @Test
    void temporaryFailureIsRetriedAfterOneFiveAndTwentyFiveSecondsAndThenReportedAsFailed() {
        email.failNext(4, "provider_unavailable", false);
        UUID id = enqueueReturned();

        dispatcher.dispatchDue();
        InMemoryNotifications.Row row = queue.row(id);
        assertEquals("queued", row.status);
        assertEquals(1, row.attempts);
        assertEquals(START.plusSeconds(1), row.nextAttemptAt);
        assertEquals("provider_unavailable", row.lastErrorCode);

        clock.advance(Duration.ofMillis(999));
        assertEquals(0, dispatcher.dispatchDue(), "срок первого повтора ещё не настал");
        clock.advance(Duration.ofMillis(1));
        dispatcher.dispatchDue();
        assertEquals(2, row.attempts);
        assertEquals(clock.instant().plusSeconds(5), row.nextAttemptAt);

        clock.advance(Duration.ofSeconds(5));
        dispatcher.dispatchDue();
        assertEquals(3, row.attempts);
        assertEquals(clock.instant().plusSeconds(25), row.nextAttemptAt);

        clock.advance(Duration.ofSeconds(25));
        dispatcher.dispatchDue();

        assertEquals("failed", row.status);
        assertEquals(4, row.attempts);
        assertTrue(row.inDeadQueue);
        assertNull(row.nextAttemptAt);
        assertEquals("provider_unavailable", row.lastErrorCode);
        assertEquals(4, email.attempts().size());
        assertEquals(0, email.accepted().size());
        assertEquals(1, tx.events().size());
        FakeNotificationTransaction.Event event = tx.events().get(0);
        assertEquals("notification.events", event.topic());
        assertEquals("Notification", event.aggregateType());
        assertEquals(id.toString(), event.aggregateId());
        assertEquals("notification.failed", event.type());
        assertEquals(1, event.schemaVersion());
        assertEquals(Map.of("notificationId", id.toString(), "channel", "email", "template", "seller-returned", "recipientUserId",
                USER.toString(), "attempts", 4, "reason", "attempts_exhausted"), event.data());
        assertEquals(3.0, count("retry"));
        assertEquals(1.0, count("failed"));
    }

    @Test
    void letterThatSucceedsOnTheRetryIsSentAndNoEventIsPublished() {
        email.failNext(2, "provider_rate_limited", false);
        UUID id = enqueueReturned();

        dispatcher.dispatchDue();
        clock.advance(Duration.ofSeconds(1));
        dispatcher.dispatchDue();
        clock.advance(Duration.ofSeconds(5));
        dispatcher.dispatchDue();

        InMemoryNotifications.Row row = queue.row(id);
        assertEquals("sent", row.status);
        assertEquals(3, row.attempts);
        assertNull(row.lastErrorCode, "после успеха код прошлой ошибки очищен");
        assertEquals(1, email.accepted().size());
        assertTrue(tx.events().isEmpty());
    }

    @Test
    void providerRejectionFailsAtOnceWithTheDeadQueueFlagAndAddressRejectedEvent() {
        email.failNext(1, "address_rejected", true);
        UUID id = enqueueReturned();

        dispatcher.dispatchDue();

        InMemoryNotifications.Row row = queue.row(id);
        assertEquals("failed", row.status);
        assertEquals(1, row.attempts);
        assertTrue(row.inDeadQueue);
        assertEquals("address_rejected", row.lastErrorCode);
        assertEquals(1, email.attempts().size());
        assertEquals(1, tx.events().size());
        assertEquals("address_rejected", tx.events().get(0).data().get("reason"));
        assertEquals(1, tx.events().get(0).data().get("attempts"));
    }

    @Test
    void eventDataNeverHoldsTheAddress() {
        email.failNext(1, "address_rejected", true);
        enqueueReturned();

        dispatcher.dispatchDue();

        String data = tx.events().get(0).data().toString();
        assertFalse(data.contains("anna@mail.example"), data);
        assertFalse(data.contains("Смирнова"), data);
        assertFalse(data.contains("Приложите"), data);
    }

    @Test
    void deactivatedRecipientGetsNoLetterAndNobodyIsToldBecauseThereIsNoOneToTell() {
        UUID id = enqueueReturned();
        contacts.deactivate(USER);

        dispatcher.dispatchDue();

        InMemoryNotifications.Row row = queue.row(id);
        assertEquals("failed", row.status);
        assertEquals("recipient_unavailable", row.lastErrorCode);
        assertEquals(0, row.attempts);
        assertFalse(row.inDeadQueue);
        assertTrue(email.attempts().isEmpty());
        assertTrue(tx.events().isEmpty());
        assertEquals(1.0, count("dropped"));
    }

    @Tag("TC-719")
    @Test
    void theDeactivationLetterGoesToTheAddressOfTheAccountThatIsNowDeactivated() {
        service.enqueue(new NotificationRequest(USER, "user-deactivated", Map.of(), UUID.randomUUID()));
        contacts.deactivate(USER);

        dispatcher.dispatchDue();

        assertEquals(1, email.accepted().size());
        OutgoingEmail mail = email.accepted().get(0);
        assertEquals(ADDRESS, mail.to());
        assertEquals("Учётная запись деактивирована", mail.subject());
        assertTrue(mail.text().startsWith("Здравствуйте, Анна Смирнова!"), mail.text());
        assertEquals("sent", queue.all().get(0).status);
        assertFalse(queue.all().get(0).source.params().toString().contains("@"), "адреса в очереди нет");
        assertEquals(1.0, count("sent"));
    }

    @Tag("TC-719")
    @Test
    void theDeactivationLetterIsDroppedWhenTheAccountIsAnonymizedBeforeTheSendAndNobodyIsTold() {
        service.enqueue(new NotificationRequest(USER, "user-deactivated", Map.of(), UUID.randomUUID()));
        contacts.deactivate(USER);
        contacts.anonymize(USER);

        dispatcher.dispatchDue();

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals("failed", row.status);
        assertEquals("recipient_unavailable", row.lastErrorCode);
        assertFalse(row.inDeadQueue);
        assertTrue(email.attempts().isEmpty());
        assertTrue(tx.events().isEmpty());
        assertEquals(1.0, count("dropped"));
    }

    @Tag("TC-719")
    @Test
    void theDeactivationLetterIsDroppedWhenTheAccountIsActiveAgainByTheTimeOfTheSend() {
        service.enqueue(new NotificationRequest(USER, "user-deactivated", Map.of(), UUID.randomUUID()));

        dispatcher.dispatchDue();

        assertEquals("failed", queue.all().get(0).status);
        assertTrue(email.attempts().isEmpty());
        assertEquals(1.0, count("dropped"));
    }

    @Tag("TC-717")
    @Test
    void theEmailChangedLettersGoToTheNewAndThePreviousAddressAndTheQueueHoldsNeither() {
        contacts.put(USER, "new-anna@mail.example", "Анна Смирнова");
        contacts.putPrevious(USER, ADDRESS, "Анна Смирнова");
        UUID event = UUID.randomUUID();
        Map<String, Object> params = Map.of("changedAt", "2026-10-26T12:48:16.100Z");
        service.enqueue(new NotificationRequest(USER, "email-changed-new", params, event));
        service.enqueue(new NotificationRequest(USER, "email-changed-previous", params, event));

        assertEquals(2, dispatcher.dispatchDue());

        List<String> recipients = email.accepted().stream().map(OutgoingEmail::to).toList();
        assertEquals(2, recipients.size());
        assertTrue(recipients.contains("new-anna@mail.example"), recipients.toString());
        assertTrue(recipients.contains(ADDRESS), recipients.toString());
        for (InMemoryNotifications.Row row : queue.all()) {
            assertEquals("sent", row.status);
            String stored = row.source.params().toString() + row.source.template();
            assertFalse(stored.contains("@"), stored);
        }
        OutgoingEmail toOld = email.accepted().stream().filter(mail -> mail.to().equals(ADDRESS)).findFirst().orElseThrow();
        assertEquals("E-mail вашей учётной записи заменён", toOld.subject());
        OutgoingEmail toNew = email.accepted().stream().filter(mail -> mail.to().equals("new-anna@mail.example")).findFirst().orElseThrow();
        assertEquals("E-mail вашей учётной записи изменён", toNew.subject());
    }

    @Tag("TC-717")
    @Test
    void whenThePreviousAddressIsGoneTheLetterToItIsDroppedWithoutAnEventAndTheOtherOneStillGoes() {
        contacts.put(USER, "new-anna@mail.example", "Анна Смирнова");
        contacts.putPrevious(USER, ADDRESS, "Анна Смирнова");
        contacts.expirePrevious(USER);
        UUID event = UUID.randomUUID();
        Map<String, Object> params = Map.of("changedAt", "2026-10-26T12:48:16.100Z");
        service.enqueue(new NotificationRequest(USER, "email-changed-new", params, event));
        service.enqueue(new NotificationRequest(USER, "email-changed-previous", params, event));

        dispatcher.dispatchDue();

        assertEquals(List.of("new-anna@mail.example"), email.accepted().stream().map(OutgoingEmail::to).toList());
        InMemoryNotifications.Row previous = queue.all().stream().filter(row -> row.source.template().equals("email-changed-previous")).findFirst()
                .orElseThrow();
        assertEquals("failed", previous.status);
        assertEquals("recipient_unavailable", previous.lastErrorCode);
        assertFalse(previous.inDeadQueue);
        assertTrue(tx.events().isEmpty(), "получателя нет, сообщать некому");
        assertEquals(1.0, count("dropped"));
        assertEquals(1.0, count("sent"));
    }

    @Test
    void notificationClaimedByAnInstantThatThenCrashesIsReturnedToTheQueueWhenTheLeaseEnds() {
        UUID id = enqueueReturned();
        email.failNext(new IllegalStateException("экземпляр упал"));

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchDue());
        assertEquals("queued", queue.row(id).status);

        clock.advance(Duration.ofSeconds(29));
        assertEquals(0, dispatcher.dispatchDue(), "пока аренда не кончилась, другой экземпляр письмо не берёт");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(1, dispatcher.dispatchDue());
        assertEquals("sent", queue.row(id).status);
        assertEquals(1, queue.row(id).attempts, "упавшая попытка не посчитана: результата она не записала");
    }

    @Test
    void oneSweepSendsNoMoreThanTheBatch() {
        for (int i = 0; i < 12; i++) {
            enqueueReturned();
        }

        assertEquals(10, dispatcher.dispatchDue());
        assertEquals(2, dispatcher.dispatchDue());
        assertEquals(0, dispatcher.dispatchDue());
        assertEquals(12, email.accepted().size());
    }

    @Test
    void lettersAreSentInOrderOfTheirDueTime() {
        UUID first = enqueueReturned();
        clock.advance(Duration.ofSeconds(1));
        UUID second = enqueueReturned();
        clock.advance(Duration.ofSeconds(1));

        dispatcher.dispatchDue();

        assertEquals(first.toString(), email.accepted().get(0).messageId());
        assertEquals(second.toString(), email.accepted().get(1).messageId());
    }

    @Test
    void outgoingEmailPrintsOnlyItsMessageIdSoTheAddressAndTextStayOutOfLogs() {
        email.failNext(1, "provider_timeout", false);
        enqueueReturned();

        dispatcher.dispatchDue();

        String printed = email.attempts().get(0).toString();
        assertEquals("OutgoingEmail[" + email.attempts().get(0).messageId() + "]", printed);
        assertFalse(printed.contains(ADDRESS), printed);
    }
}
