package dgm.platform.notification.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.testing.InMemoryNotifications;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Письмо владельцу о входе по коду из SMS (FT-1.7): время входа берётся из конверта события. */
class SmsLoginHandlerTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final Instant LOGIN = Instant.parse("2026-10-11T17:05:09.123Z");

    private InMemoryNotifications queue;
    private SmsLoginHandler handler;

    @BeforeEach
    void setUp() {
        queue = new InMemoryNotifications();
        handler = new SmsLoginHandler(new NotificationService(queue, new Letters(ZoneId.of("Europe/Moscow"), "МСК"), new ManualClock(LOGIN)));
    }

    private static EventEnvelope event(Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "platform-service", "user.sms-login", LOGIN, USER.toString(), 1, "User",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    @Test
    void handlerDeclaresTheSmsLoginEventOfIdentityTopicAndTheNotificationModule() {
        assertEquals("identity.events", handler.topic());
        assertEquals(Set.of("user.sms-login"), handler.types());
        assertEquals("notification", handler.module());
        assertEquals(1, handler.maxSchemaVersion());
        assertEquals("notification-handler", handler.name());
    }

    @Test
    void eventQueuesTheLoginLetterWithTheTimeOfTheEvent() {
        EventEnvelope event = event(Map.of("userId", USER.toString()));

        assertEquals(HandleResult.done(), handler.handle(event));

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals(USER, row.source.userId());
        assertEquals("user-sms-login", row.source.template());
        assertEquals(Map.of("loggedInAt", "2026-10-11T17:05:09.123Z"), row.source.params());
        assertEquals(event.id(), row.source.sourceEventId());
    }

    @Test
    void sameEventHandledTwiceGivesOneLetter() {
        EventEnvelope event = event(Map.of("userId", USER.toString()));

        handler.handle(event);
        HandleResult second = handler.handle(event);

        assertFalse(second.applied());
        assertEquals(1, queue.all().size());
    }

    @Test
    void twoLoginsGiveTwoLetters() {
        handler.handle(event(Map.of("userId", USER.toString())));
        handler.handle(event(Map.of("userId", USER.toString())));

        assertEquals(2, queue.all().size());
    }

    @Test
    void eventWithoutUserGoesStraightToDlq() {
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of())));
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of("userId", "42"))));
        assertEquals(0, queue.all().size());
    }
}
