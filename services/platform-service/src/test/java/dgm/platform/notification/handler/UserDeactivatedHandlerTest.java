package dgm.platform.notification.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.letter.Letter;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.testing.InMemoryNotifications;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Письмо о деактивации учётной записи (FT-11.0, шаг 16 Ф4): одно письмо владельцу по событию, без параметров, без имени администратора. */
@Tag("TC-719")
class UserDeactivatedHandlerTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID ADMIN = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private final Letters letters = new Letters(ZoneId.of("Europe/Moscow"), "МСК");
    private InMemoryNotifications queue;
    private UserDeactivatedHandler handler;

    @BeforeEach
    void setUp() {
        queue = new InMemoryNotifications();
        handler = new UserDeactivatedHandler(new NotificationService(queue, letters, new ManualClock(NOW)));
    }

    private static EventEnvelope event(Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "platform-service", "user.deactivated", NOW, USER.toString(), 1, "User",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    private static Map<String, Object> data() {
        return Map.of("userId", USER.toString(), "deactivatedBy", ADMIN.toString());
    }

    @Test
    void handlerDeclaresTheDeactivationEventOfIdentityTopicAndTheNotificationModule() {
        assertEquals("identity.events", handler.topic());
        assertEquals(Set.of("user.deactivated"), handler.types());
        assertEquals("notification", handler.module());
        assertEquals(1, handler.maxSchemaVersion());
        assertEquals("notification-handler", handler.name());
    }

    @Test
    void eventQueuesOneLetterWithoutParametersToTheDeactivatedUser() {
        EventEnvelope event = event(data());

        assertEquals(HandleResult.done(), handler.handle(event));

        assertEquals(1, queue.all().size());
        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals(USER, row.source.userId());
        assertEquals("user-deactivated", row.source.template());
        assertEquals(Map.of(), row.source.params());
        assertEquals(event.id(), row.source.sourceEventId());
        assertFalse(row.source.params().toString().contains(ADMIN.toString()), "кто деактивировал, в очередь не попадает");
    }

    @Test
    void sameEventHandledTwiceGivesOneLetter() {
        EventEnvelope event = event(data());

        handler.handle(event);
        HandleResult second = handler.handle(event);

        assertFalse(second.applied());
        assertEquals(1, queue.all().size());
    }

    @Test
    void letterTellsTheUserAndNamesNobodyElse() {
        handler.handle(event(data()));

        Letter letter = letters.render("user-deactivated", "Анна Смирнова", queue.all().get(0).source.params());

        assertEquals("Учётная запись деактивирована", letter.subject());
        assertTrue(letter.text().startsWith("Здравствуйте, Анна Смирнова!"), letter.text());
        assertTrue(letter.text().contains("деактивирована"), letter.text());
        assertTrue(letter.text().contains("обратитесь в поддержку"), letter.text());
        assertFalse(letter.text().contains(ADMIN.toString()), letter.text());
        assertFalse(letter.text().contains("{"), letter.text());
    }

    @Test
    void theLetterGoesOnlyToTheAddressOfADeactivatedAccount() {
        assertTrue(letters.toDeactivatedAccount().contains("user-deactivated"));
        assertFalse(letters.toDeactivatedAccount().contains("seller-returned"));
        assertFalse(letters.toPreviousAddress().contains("user-deactivated"));
    }

    @Test
    void eventWithoutAValidUserGoesStraightToDlq() {
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of("deactivatedBy", ADMIN.toString()))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of("userId", "42"))));
        assertTrue(queue.all().isEmpty());
    }
}
