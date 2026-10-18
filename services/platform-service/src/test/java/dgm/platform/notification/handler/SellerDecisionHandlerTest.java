package dgm.platform.notification.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.api.NotificationRequest;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.testing.InMemoryNotifications;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Обработчик решений по заявке продавца: событие catalog-service превращается в письмо заявителю (FT-2.2, шаг 8 Ф4). */
@Tag("TC-219")
class SellerDecisionHandlerTest {

    private static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000c1");
    private static final UUID MODERATOR = UUID.fromString("0199e0a0-0000-4000-8000-0000000000d1");
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private InMemoryNotifications queue;
    private SellerDecisionHandler handler;

    @BeforeEach
    void setUp() {
        queue = new InMemoryNotifications();
        NotificationService service = new NotificationService(queue, new Letters(ZoneId.of("Europe/Moscow"), "МСК"), new ManualClock(NOW));
        handler = new SellerDecisionHandler(service);
    }

    static EventEnvelope event(String type, Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "catalog-service", type, NOW, SELLER.toString(), 1, "Seller",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    private static Map<String, Object> data(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sellerId", SELLER.toString());
        map.put("userId", USER.toString());
        map.put("moderatorId", MODERATOR.toString());
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void handlerDeclaresTheSellerEventsOfCatalogTopicAndTheNotificationModule() {
        assertEquals("catalog.events", handler.topic());
        assertEquals(Set.of("seller.approved", "seller.rejected", "seller.returned"), handler.types());
        assertEquals("notification", handler.module());
        assertEquals(1, handler.maxSchemaVersion());
        assertEquals("notification-handler", handler.name());
    }

    @Test
    void approvedEventQueuesTheApprovedLetterForTheOwner() {
        EventEnvelope event = event("seller.approved", data("sellerType", "self_employed"));

        assertEquals(HandleResult.done(), handler.handle(event));

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals(USER, row.source.userId());
        assertEquals("seller-approved", row.source.template());
        assertEquals(Map.of("sellerType", "self_employed"), row.source.params());
        assertEquals(event.id(), row.source.sourceEventId());
    }

    @Test
    void rejectedEventQueuesTheReasonAndTheNextApplicationTime() {
        handler.handle(event("seller.rejected", data("reason", "Нет скана паспорта", "nextApplicationAt", "2026-10-12T12:00:00Z")));

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals("seller-rejected", row.source.template());
        assertEquals(Map.of("reason", "Нет скана паспорта", "nextApplicationAt", "2026-10-12T12:00:00Z"), row.source.params());
    }

    @Test
    void returnedEventQueuesTheComment() {
        handler.handle(event("seller.returned", data("comment", "Приложите выписку")));

        InMemoryNotifications.Row row = queue.all().get(0);
        assertEquals("seller-returned", row.source.template());
        assertEquals(Map.of("comment", "Приложите выписку"), row.source.params());
    }

    @Test
    void identifiersOfTheSellerAndTheModeratorDoNotReachTheQueue() {
        handler.handle(event("seller.returned", data("comment", "x")));

        String params = queue.all().get(0).source.params().toString();
        assertFalse(params.contains(SELLER.toString()), params);
        assertFalse(params.contains(MODERATOR.toString()), params);
    }

    @Test
    void sameEventHandledTwiceGivesOneLetter() {
        EventEnvelope event = event("seller.returned", data("comment", "x"));

        assertEquals(HandleResult.done(), handler.handle(event));
        HandleResult second = handler.handle(event);

        assertFalse(second.applied());
        assertEquals("duplicate-notification", second.reason());
        assertEquals(1, queue.all().size());
    }

    @Test
    void eventWithoutRequiredFieldOrWithWrongTypeGoesStraightToDlq() {
        Map<String, Object> noUser = data("comment", "x");
        noUser.remove("userId");
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.returned", noUser)));
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.returned", data("userId", "не uuid", "comment", "x"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.returned", data())));
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.returned", data("comment", 5))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.rejected", data("reason", "x"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.approved", data("sellerType", "ooo"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.blocked", data())));

        assertEquals(0, queue.all().size());
    }

    @Test
    void textLongerThanTheSchemaAllowsIsFatal() {
        assertThrows(FatalEventException.class, () -> handler.handle(event("seller.returned", data("comment", "я".repeat(1001)))));
        assertEquals(HandleResult.done(), handler.handle(event("seller.returned", data("comment", "я".repeat(1000)))));
    }

    @Test
    void errorMessageNamesTheFieldButNotTheModeratorText() {
        FatalEventException e = assertThrows(FatalEventException.class,
                () -> handler.handle(event("seller.rejected", data("reason", "Причина с секретом", "nextApplicationAt", "завтра"))));

        assertTrue(e.getMessage().contains("nextApplicationAt"), e.getMessage());
        assertFalse(e.getMessage().contains("секрет"), e.getMessage());
        assertFalse(e.getMessage().contains("завтра"), e.getMessage());
    }

    @Test
    void requestToStringHidesTheParameters() {
        NotificationRequest request = new NotificationRequest(USER, "seller-returned", Map.of("comment", "тайна"), null);

        assertFalse(request.toString().contains("тайна"));
    }
}
