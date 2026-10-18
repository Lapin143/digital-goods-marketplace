package dgm.platform.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.platform.notification.api.InvalidNotificationException;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.testing.InMemoryNotifications;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Постановка уведомлений в очередь: проверка по шаблону, дедупликация по событию, адреса в очереди нет (INV-44, ADR-006). */
@Tag("TC-220")
class NotificationServiceTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID EVENT = UUID.fromString("0199e0a0-0000-4000-8000-0000000000e1");
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private InMemoryNotifications queue;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        queue = new InMemoryNotifications();
        service = new NotificationService(queue, new Letters(ZoneId.of("Europe/Moscow"), "МСК"), new ManualClock(NOW));
    }

    private static NotificationRequest returned(UUID event) {
        return new NotificationRequest(USER, "seller-returned", Map.of("comment", "Исправьте"), event);
    }

    @Test
    void requestBecomesAQueuedRowDueAtOnceWithTheSourceEvent() {
        assertTrue(service.enqueue(returned(EVENT)));

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals(1, queue.all().size());
        assertEquals(USER, row.source.userId());
        assertEquals("seller-returned", row.source.template());
        assertEquals(EVENT, row.source.sourceEventId());
        assertEquals(Map.of("comment", "Исправьте"), row.source.params());
        assertEquals("queued", row.status);
        assertEquals(NOW, row.nextAttemptAt);
        assertEquals(0, row.attempts);
        assertEquals(7, row.source.id().version(), "идентификатор UUIDv7");
    }

    @Test
    void theSameEventAndTemplateGiveOneNotification() {
        assertTrue(service.enqueue(returned(EVENT)));
        assertFalse(service.enqueue(returned(EVENT)));

        assertEquals(1, queue.all().size());
    }

    @Test
    void anotherEventOrAnotherTemplateGivesAnotherNotification() {
        assertTrue(service.enqueue(returned(EVENT)));
        assertTrue(service.enqueue(returned(UUID.randomUUID())));
        assertTrue(service.enqueue(new NotificationRequest(USER, "user-sms-login", Map.of("loggedInAt", "2026-10-11T11:00:00Z"), EVENT)));

        assertEquals(3, queue.all().size());
    }

    @Test
    void requestWithoutSourceEventIsNeverDeduplicated() {
        assertTrue(service.enqueue(returned(null)));
        assertTrue(service.enqueue(returned(null)));

        assertEquals(2, queue.all().size());
    }

    @Test
    void invalidRequestIsRejectedAndNothingIsQueued() {
        assertThrows(InvalidNotificationException.class, () -> service.enqueue(new NotificationRequest(USER, "order-paid", Map.of(), EVENT)));
        assertThrows(InvalidNotificationException.class,
                () -> service.enqueue(new NotificationRequest(USER, "seller-returned", Map.of("comment", " "), EVENT)));
        assertThrows(InvalidNotificationException.class,
                () -> service.enqueue(new NotificationRequest(USER, "Seller Returned", Map.of("comment", "x"), EVENT)));

        assertEquals(0, queue.all().size());
    }

    @Test
    void parametersCannotCarryPersonalData() {
        for (String key : new String[] {"email", "phone", "name", "address", "recipient"}) {
            InvalidNotificationException e = assertThrows(InvalidNotificationException.class,
                    () -> service.enqueue(new NotificationRequest(USER, "seller-returned", Map.of("comment", "x", key, "a@b.example"), EVENT)));
            assertFalse(e.getMessage().contains("a@b.example"), e.getMessage());
        }

        assertEquals(0, queue.all().size());
    }

    @Test
    void requestToStringHidesTheParameters() {
        String text = returned(EVENT).toString();

        assertFalse(text.contains("Исправьте"), text);
        assertTrue(text.contains("seller-returned"), text);
    }
}
