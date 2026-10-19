package dgm.delivery.delivery.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.domain.DeliveryAddress;
import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Потребитель {@code user.anonymized} (TC-605, INV-44): адреса открытых выдач пользователя заменяются на зарезервированный адрес, закрытые
 * выдачи и чужие выдачи не меняются, повтор безвреден.
 */
@Tag("TC-605")
class UserAnonymizedHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");

    private FakeDeliveries deliveries;
    private UserAnonymizedHandler handler;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        handler = new UserAnonymizedHandler(deliveries);
    }

    private static UUID order(int n) {
        return UUID.fromString("0199e0a0-0000-7000-8000-00000000030" + n);
    }

    private static EventEnvelope event(Map<String, Object> data) {
        return new EventEnvelope(UUID.fromString("0199e0a0-0000-7000-8000-000000000b01"), "platform-service", "user.anonymized", NOW, BUYER.toString(), 1,
                "User", null, "corr-1", data);
    }

    private static EventEnvelope anonymized(UUID user) {
        return event(Map.of("userId", user.toString()));
    }

    @Test
    void theHandlerNamesItsTopicTypeAndTheComponent() {
        assertEquals("delivery-domain", handler.name());
        assertEquals("identity.events", handler.topic());
        assertEquals(Set.of("user.anonymized"), handler.types());
        assertEquals(1, handler.maxSchemaVersion());
    }

    @Test
    void queuedAndSentDeliveriesOfTheUserGetTheReservedAddressAndTheOthersStayAsTheyAre() {
        deliveries.insertPrimary(UUID.fromString("0199e0a0-0000-7000-8000-000000000a01"), order(1), BUYER, "anna@mail.example", "Игра", 1, NOW);
        deliveries.insertPrimary(UUID.fromString("0199e0a0-0000-7000-8000-000000000a02"), order(2), BUYER, "anna@mail.example", "Игра", 1, NOW);
        deliveries.insertPrimary(UUID.fromString("0199e0a0-0000-7000-8000-000000000a03"), order(3), OTHER, "boris@mail.example", "Игра", 1, NOW);
        UUID sent = UUID.fromString("0199e0a0-0000-7000-8000-000000000a02");
        deliveries.markSent(sent, 0, NOW, NOW);

        HandleResult result = handler.handle(anonymized(BUYER));

        assertTrue(result.applied());
        assertEquals(DeliveryAddress.ANONYMIZED, deliveries.row(UUID.fromString("0199e0a0-0000-7000-8000-000000000a01")).address);
        assertEquals(DeliveryAddress.ANONYMIZED, deliveries.row(sent).address);
        assertEquals("boris@mail.example", deliveries.row(UUID.fromString("0199e0a0-0000-7000-8000-000000000a03")).address);
    }

    @Test
    void aFailedDeliveryKeepsItsAddress() {
        UUID id = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
        deliveries.insertPrimary(id, order(1), BUYER, "anna@mail.example", "Игра", 1, NOW);
        deliveries.markFailed(id, 0, "permanent_error", "address_rejected", "address_rejected", NOW, NOW);

        HandleResult result = handler.handle(anonymized(BUYER));

        assertFalse(result.applied());
        assertEquals("no-open-deliveries", result.reason());
        assertEquals("anna@mail.example", deliveries.row(id).address);
    }

    @Test
    void theSameEventTwiceChangesNothingTheSecondTime() {
        deliveries.insertPrimary(UUID.fromString("0199e0a0-0000-7000-8000-000000000a01"), order(1), BUYER, "anna@mail.example", "Игра", 1, NOW);
        assertTrue(handler.handle(anonymized(BUYER)).applied());

        HandleResult second = handler.handle(anonymized(BUYER));

        assertFalse(second.applied());
        assertEquals("no-open-deliveries", second.reason());
    }

    @Test
    void aUserWithoutDeliveriesIsAnIgnoredEvent() {
        HandleResult result = handler.handle(anonymized(BUYER));

        assertFalse(result.applied());
        assertEquals("no-open-deliveries", result.reason());
    }

    @Test
    void anEventWithoutAUserIdentifierIsFatal() {
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of())));
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of("userId", "не-идентификатор"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event(Map.of("userId", BUYER.toString().toUpperCase()))));
    }
}
