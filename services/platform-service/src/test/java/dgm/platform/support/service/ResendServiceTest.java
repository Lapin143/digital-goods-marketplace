package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.BUYER_EMAIL;
import static dgm.platform.support.testing.SupportWorld.OPERATOR;
import static dgm.platform.support.testing.SupportWorld.OPERATOR_2;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.ResendAccepted;
import dgm.platform.support.testing.FakeOrders;
import dgm.platform.support.testing.RecordingOutbox;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Повторная отправка ключа по обращению ({@code resendKeyForTicket}, OP-72): быстрые проверки, запись решения до вызова {@code order-service},
 * ключ идемпотентности вызова, отказы и недоступность соседа, статус «в очереди» в модели чтения и повтор после неудачи доставки (US-7.3,
 * Р-165, Р-170). В записи аудита нет ни адреса покупателя, ни ключа (INV-23, INV-44).
 */
@Tag("TC-708")
@Tag("TC-709")
class ResendServiceTest {

    private static final Operator FIRST = new Operator(OPERATOR, "203.0.113.7");
    private static final Operator SECOND = new Operator(OPERATOR_2, "203.0.113.8");

    private SupportWorld world;
    private UUID order;
    private UUID ticket;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
        ticket = world.tickets.create(BUYER, order).id();
        world.queue.take(FIRST, ticket);
    }

    private OrderView view() {
        return world.repository.findOrderView(order).orElseThrow();
    }

    private List<RecordingOutbox.Event> resendRecords() {
        return world.outbox.events("audit.recorded").stream().filter(e -> "support.resend_requested".equals(e.data().get("action"))).toList();
    }

    @Test
    void anAcceptedResendUpdatesTheAddressOnceAndMarksTheDeliveryQueued() {
        ResendAccepted accepted = world.resend.resend(FIRST, ticket);

        assertEquals(new ResendAccepted(ticket, order), accepted);
        List<FakeOrders.Call> calls = world.orders.calls();
        assertEquals(1, calls.size());
        assertEquals(order, calls.get(0).orderId());
        assertEquals(ticket, calls.get(0).ticketId());
        assertEquals(BUYER_EMAIL, calls.get(0).email(), "адрес берётся из учётной записи покупателя, а не вводится оператором");
        assertEquals(Optional.of(OrderView.QUEUED), view().deliveryStatus());
        assertEquals(Optional.of("resend"), Optional.ofNullable(world.repository.tickets().get(0).action));
        assertEquals("in_progress", world.repository.tickets().get(0).status, "обращение остаётся в работе до результата выдачи");
        assertEquals(1.0, world.metrics.get("support_resend_total").tag("result", "accepted").counter().count());
    }

    @Test
    void theDecisionIsRecordedWithoutTheAddressOrAnyKey() {
        world.resend.resend(FIRST, ticket);

        List<RecordingOutbox.Event> records = resendRecords();
        assertEquals(1, records.size(), "одна запись аудита на решение");
        Map<String, Object> data = records.get(0).data();
        assertEquals(OPERATOR.toString(), data.get("actorId"));
        assertEquals("support-operator", data.get("actorRole"));
        assertEquals("203.0.113.7", data.get("actorIp"));
        assertEquals("ticket", data.get("objectType"));
        assertEquals(ticket.toString(), data.get("objectId"));
        assertEquals(List.of("action", "orderId", "source"), ((List<?>) data.get("changes")).stream().map(c -> ((Map<?, ?>) c).get("field")).toList());
        assertFalse(data.toString().contains(BUYER_EMAIL), "адреса покупателя в аудите нет");
        assertFalse(data.toString().contains(world.orders.calls().get(0).key().toString()), "ключа идемпотентности в аудите нет");
        assertFalse(data.toString().contains("@"));
    }

    @Test
    void aSecondResendWhileTheFirstIsQueuedIsRefusedWithoutACallOrARecord() {
        world.resend.resend(FIRST, ticket);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket));

        assertEquals(ProblemType.DELIVERY_IN_PROGRESS, refused.type());
        assertEquals(1, world.orders.calls().size(), "второго вызова нет");
        assertEquals(1, resendRecords().size(), "второй записи нет");
        assertEquals(1.0, world.metrics.get("support_resend_total").tag("result", "delivery-in-progress").counter().count());
    }

    @Test
    void onlyTheOperatorWhoTookTheTicketCanResend() {
        ProblemException other = assertThrows(ProblemException.class, () -> world.resend.resend(SECOND, ticket));
        assertEquals(ProblemType.TICKET_NOT_IN_PROGRESS, other.type());

        world.queue.release(FIRST, ticket);
        ProblemException inQueue = assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket));
        assertEquals(ProblemType.TICKET_NOT_IN_PROGRESS, inQueue.type());

        assertTrue(world.orders.calls().isEmpty());
        assertTrue(resendRecords().isEmpty());
    }

    @Test
    void anUnknownTicketIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, UUID.randomUUID())).type());
        assertTrue(world.orders.calls().isEmpty());
    }

    @Test
    void anOrderThatIsNotIssuedIsAStateConflictWithTheCurrentStatus() {
        UUID paid = world.paidOrder(BUYER);
        UUID paidTicket = world.overdueTicketOf(paid);
        world.queue.take(FIRST, paidTicket);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, paidTicket));

        assertEquals(ProblemType.STATE_CONFLICT, refused.type());
        assertEquals("paid", refused.extensions().get("currentStatus"));
        assertTrue(world.orders.calls().isEmpty());
        assertTrue(resendRecords().isEmpty());
    }

    @Test
    void aBuyerWithoutAnActiveAccountHasNoAddressToSendTo() {
        world.contacts.deactivate(BUYER);

        ProblemException refused = assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket));

        assertEquals(ProblemType.STATE_CONFLICT, refused.type());
        assertTrue(world.orders.calls().isEmpty());
        assertTrue(resendRecords().isEmpty(), "решения нет, пока нечего отправлять");
    }

    @Test
    void theNeighbourRefusingBySenseIsMappedAndTheDecisionStays() {
        world.orders.refuse(404, "not-found");
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket)).type());

        world.orders.refuse(409, "state-conflict");
        assertEquals(ProblemType.STATE_CONFLICT, assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket)).type());

        world.orders.refuse(409, "request-in-progress");
        ProblemException busy = assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket));
        assertEquals(ProblemType.REQUEST_IN_PROGRESS, busy.type());
        assertEquals("1", busy.headers().get("Retry-After"));

        assertEquals(Optional.empty(), view().deliveryStatus(), "статус «в очереди» ставится только после принятого вызова");
        assertEquals(3, resendRecords().size(), "решение записано до каждого вызова, записи «не удалось» нет");
    }

    @Test
    void aRefusalThatIsThePlatformsOwnMistakeIsTheOperatorsServiceUnavailable() {
        world.orders.refuse(400, "validation-failed");

        ProblemException e = assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertFalse(e.getMessage().contains("validation"), "детали отказа оператору не показываются");
    }

    @Test
    void anUnavailableNeighbourGivesA503AndARetryReusesTheKeyWithoutADuplicate() {
        world.orders.unavailableAfterEffect();
        ProblemException first = assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, first.type());
        assertEquals("5", first.headers().get("Retry-After"));
        assertEquals(Optional.empty(), view().deliveryStatus());
        assertEquals(1, resendRecords().size(), "решение записано до вызова и остаётся");

        world.orders.recover();
        world.clock.advance(Duration.ofSeconds(7));
        world.resend.resend(FIRST, ticket);

        List<FakeOrders.Call> calls = world.orders.calls();
        assertEquals(2, calls.size());
        assertEquals(calls.get(0).key(), calls.get(1).key(), "повтор идёт с тем же ключом");
        assertEquals(1, world.orders.effects(), "адрес в заказе обновлён и событие опубликовано один раз");
        assertEquals(Optional.of(OrderView.QUEUED), view().deliveryStatus());
    }

    @Test
    void afterAFailedDeliveryTheTicketStaysOpenAndAResendIsPossibleAgainWithANewKey() {
        world.resend.resend(FIRST, ticket);
        world.clock.advance(Duration.ofMinutes(2));
        world.events.onDeliveryFailed(order, "attempts_exhausted", world.clock.instant());

        assertEquals(Optional.of(OrderView.FAILED), view().deliveryStatus());
        assertEquals(1, world.repository.ticketsOf(order).size(), "нового обращения нет: по заказу уже есть открытое");
        assertEquals("in_progress", world.repository.ticketsOf(order).get(0).status);

        world.clock.advance(Duration.ofMinutes(1));
        world.resend.resend(FIRST, ticket);

        List<FakeOrders.Call> calls = world.orders.calls();
        assertEquals(2, calls.size());
        assertNotEquals(calls.get(0).key(), calls.get(1).key(), "новое событие выдачи даёт новый ключ");
        assertEquals(2, world.orders.effects());
        assertEquals(Optional.of(OrderView.QUEUED), view().deliveryStatus());
        assertEquals(2, resendRecords().size());
    }

    @Test
    void aDeliveredLetterResolvesTheTicketAndEndsResends() {
        world.resend.resend(FIRST, ticket);
        world.clock.advance(Duration.ofMinutes(1));
        world.events.onDeliveryDelivered(order, world.clock.instant());

        assertEquals("resolved", world.repository.ticketsOf(order).get(0).status);
        assertEquals(ProblemType.TICKET_NOT_IN_PROGRESS, assertThrows(ProblemException.class, () -> world.resend.resend(FIRST, ticket)).type());
        assertEquals(1, world.orders.calls().size());
    }
}
