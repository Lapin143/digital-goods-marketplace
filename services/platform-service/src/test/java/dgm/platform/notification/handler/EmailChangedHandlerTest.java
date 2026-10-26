package dgm.platform.notification.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.testing.InMemoryNotifications;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Письма о смене e-mail на новый и прежний адрес (FT-11.0, UC-04, 6a.6): два шаблона из одного события, время смены из конверта, адресов нигде нет. */
@Tag("TC-717")
class EmailChangedHandlerTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID TICKET = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2");
    private static final UUID OPERATOR = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b3");
    private static final Instant CHANGED = Instant.parse("2026-10-26T12:48:16.100Z");

    private InMemoryNotifications queue;
    private EmailChangedHandler handler;

    @BeforeEach
    void setUp() {
        queue = new InMemoryNotifications();
        handler = new EmailChangedHandler(new NotificationService(queue, new Letters(ZoneId.of("Europe/Moscow"), "МСК"), new ManualClock(CHANGED)));
    }

    private static Map<String, Object> data() {
        return Map.of("userId", USER.toString(), "ticketId", TICKET.toString(), "operatorId", OPERATOR.toString());
    }

    private static EventEnvelope event(Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "platform-service", "user.email-changed", CHANGED, USER.toString(), 1, "User",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    @Test
    void handlerDeclaresTheEmailChangedEventOfIdentityTopicAndTheNotificationModule() {
        assertEquals("identity.events", handler.topic());
        assertEquals(Set.of("user.email-changed"), handler.types());
        assertEquals("notification", handler.module());
        assertEquals(1, handler.maxSchemaVersion());
        assertEquals("notification-handler", handler.name());
    }

    @Test
    void oneEventQueuesTheLetterToTheNewAndTheLetterToThePreviousAddressWithTheChangeTime() {
        EventEnvelope event = event(data());

        assertEquals(HandleResult.done(), handler.handle(event));

        assertEquals(2, queue.all().size());
        List<String> templates = queue.all().stream().map(row -> row.source.template()).toList();
        assertEquals(List.of("email-changed-new", "email-changed-previous"), templates);
        for (InMemoryNotifications.Row row : queue.all()) {
            assertEquals(USER, row.source.userId());
            assertEquals(Map.of("changedAt", "2026-10-26T12:48:16.100Z"), row.source.params());
            assertEquals(event.id(), row.source.sourceEventId());
            assertFalse(row.source.params().toString().contains("@"), "адресов в очереди нет");
        }
    }

    @Test
    void sameEventHandledTwiceGivesTwoLettersAndNotFour() {
        EventEnvelope event = event(data());

        handler.handle(event);
        HandleResult second = handler.handle(event);

        assertFalse(second.applied());
        assertEquals(2, queue.all().size());
    }

    @Test
    void twoChangesGiveFourLetters() {
        handler.handle(event(data()));
        handler.handle(event(data()));

        assertEquals(4, queue.all().size());
    }

    @Test
    void eventWithoutAValidUserGoesStraightToDlq() {
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of("ticketId", TICKET.toString()))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of("userId", "42"))));
        assertEquals(0, queue.all().size());
        assertTrue(queue.all().isEmpty());
    }
}
