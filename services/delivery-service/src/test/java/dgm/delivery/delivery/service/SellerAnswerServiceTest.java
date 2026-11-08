package dgm.delivery.delivery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeSellerRequests;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.client.SellerKeyStore;
import dgm.delivery.delivery.domain.KeyStoreResult;
import dgm.delivery.delivery.domain.SellerRequestSeed;
import dgm.delivery.delivery.service.SellerAnswerService.Answer;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Приём ответа продавца (TC-964, TC-966, TC-967, TC-968, ADR-028, Р-190, Р-191): аренда на приём ответа, передача ключей в хранилище вне транзакции, переход в
 * «ответ получен»; ответ, начатый до конца окна, заканчивается и тайм-аут его не прерывает, начатый после окна получает 409; недоступное хранилище не
 * теряет ключи (аренда снимается, 503); повтор тех же ключей, другие ключи, чужой запрос, отказ продавца.
 */
@Tag("TC-964")
@Tag("TC-966")
@Tag("TC-967")
@Tag("TC-968")
class SellerAnswerServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
    private static final UUID DELIVERY = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final UUID REQUEST = UUID.fromString("0199e0a0-0000-7000-8000-000000000b01");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID OTHER_SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final List<String> KEYS = List.of("SELLR-KEY00-00001", "SELLR-KEY00-00002");

    /** Хранилище ключей без соседа: отвечает по сценарию, запоминает вызовы, умеет выполнить действие «во время» вызова. */
    private static final class KeyStore implements SellerKeyStore {
        final List<UUID> orders = new ArrayList<>();
        final List<List<String>> values = new ArrayList<>();
        Supplier<KeyStoreResult> reply = () -> new KeyStoreResult.Stored(2);
        Runnable during = () -> { };

        @Override
        public KeyStoreResult store(UUID orderId, UUID requestId, List<String> keys) {
            orders.add(orderId);
            values.add(keys);
            during.run();
            return reply.get();
        }
    }

    private FakeDeliveries deliveries;
    private FakeSellerRequests requests;
    private FakeTransaction transaction;
    private ManualClock clock;
    private KeyStore keyStore;
    private SimpleMeterRegistry meters;
    private SellerRequestOutcomes outcomes;
    private SellerAnswerService service;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        requests = new FakeSellerRequests(deliveries);
        transaction = new FakeTransaction();
        clock = new ManualClock(T0);
        keyStore = new KeyStore();
        meters = new SimpleMeterRegistry();
        SellerSettings settings = SellerSettings.defaults();
        outcomes = new SellerRequestOutcomes(requests, deliveries, transaction, settings, clock, meters);
        service = new SellerAnswerService(requests, keyStore, outcomes, settings, clock, meters);
        deliveries.insertPrimary(DELIVERY, ORDER, BUYER, "anna@mail.example", "Игра", 2, T0);
        deliveries.insertWatch(ORDER, T0.plus(Duration.ofMinutes(30)), T0);
        requests.insert(DELIVERY, REQUEST, new SellerRequestSeed(SELLER, PRODUCT, 1042), T0.plus(WINDOW), T0);
    }

    private SellerAnswerService.Result keys() {
        return service.answer(SELLER, REQUEST, new Answer.Keys(KEYS));
    }

    private ProblemException refused(Runnable call) {
        return assertThrows(ProblemException.class, call::run);
    }

    @Test
    void theAnswerInTimeStoresTheKeysOutsideATransactionAndMovesTheRequestToAnswered() {
        clock.advance(Duration.ofSeconds(3));

        SellerAnswerService.Result result = keys();

        assertEquals(new SellerAnswerService.Result(REQUEST, "answered", 2), result);
        assertEquals(List.of(ORDER), keyStore.orders, "ключи уходят по заказу запроса");
        assertEquals(List.of(KEYS), keyStore.values);
        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("answered", row.status);
        assertNull(row.leaseUntil, "аренда снята");
        assertEquals(clock.instant(), row.answeredAt);
        assertEquals("queued", deliveries.row(DELIVERY).status, "выдача осталась в очереди: письмо отправит диспетчер");
        assertTrue(transaction.published().isEmpty(), "ответ в срок событий не пишет");
        assertEquals(1.0, meters.counter("delivery_seller_requests_total", "outcome", "answered").count());
    }

    @Test
    void anAcceptedRequestIsAnsweredTheSameWay() {
        requests.accept(DELIVERY, 0, T0);

        assertEquals("answered", keys().status());

        assertEquals("answered", requests.row(DELIVERY).status);
    }

    @Test
    void theLeaseHeldWhileTheKeysAreStoredKeepsTheTimeoutFromCancellingTheOrder() {
        clock.advance(WINDOW.minusSeconds(1));
        int[] closedDuring = {-1};
        keyStore.during = () -> {
            // ответ начат за секунду до конца окна, хранилище отвечает дольше: окно успевает закрыться
            clock.advance(Duration.ofSeconds(5));
            closedDuring[0] = outcomes.expireDue();
        };

        SellerAnswerService.Result result = keys();

        assertEquals(0, closedDuring[0], "запрос с арендой тайм-аут не берёт");
        assertEquals("answered", result.status());
        assertEquals("answered", requests.row(DELIVERY).status);
        assertEquals(0, outcomes.expireDue(), "после ответа запрос тайм-ауту не принадлежит");
        assertEquals("queued", deliveries.row(DELIVERY).status);
        assertTrue(transaction.published(DeliveryEvents.SELLER_TIMEOUT).isEmpty(), "заказ не отменён, ключ не потерян");
    }

    @Test
    void whenTheTimeoutIsFirstTheAnswerAfterTheWindowGets409AndTheKeysAreNotSent() {
        clock.advance(WINDOW);
        assertEquals(1, outcomes.expireDue());

        ProblemException e = refused(() -> keys());

        assertEquals(ProblemType.REQUEST_EXPIRED, e.type());
        assertTrue(keyStore.orders.isEmpty(), "ключи хранилищу не передаются: заказ отменён");
        assertEquals("timeout", requests.row(DELIVERY).status);
    }

    @Test
    void anAnswerStartedAfterTheEndOfTheWindowGets409EvenBeforeTheTimeoutRuns() {
        clock.advance(WINDOW.plusSeconds(1));

        ProblemException e = refused(() -> keys());

        assertEquals(ProblemType.REQUEST_EXPIRED, e.type());
        assertTrue(keyStore.orders.isEmpty());
        assertNull(requests.row(DELIVERY).leaseUntil, "аренда не взята");
        assertEquals("requested", requests.row(DELIVERY).status, "закрыть запрос по тайм-ауту дело исполнителя тайм-аута");
    }

    @Test
    void whenTheLeaseOfTheAnswerExpiredBeforeTheKeysWereStoredTheTimeoutMayClose() {
        clock.advance(WINDOW.minusSeconds(1));
        requests.lease(REQUEST, clock.instant(), clock.instant().plus(SellerSettings.defaults().answerLease()));
        clock.advance(SellerSettings.defaults().answerLease().plusSeconds(2));

        assertEquals(1, outcomes.expireDue(), "аренда истекла, а ключи не сохранены: запрос закрывается тайм-аутом");

        assertEquals("timeout", requests.row(DELIVERY).status);
    }

    @Test
    void anUnavailableKeyStoreReleasesTheLeaseAndTheSellerRepeatsTheCall() {
        keyStore.reply = () -> new KeyStoreResult.Unavailable("inventory_unavailable");

        ProblemException e = refused(() -> keys());

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("requested", row.status, "ключи не потеряны: запрос ждёт повторного ответа");
        assertNull(row.leaseUntil, "аренда снята");

        keyStore.reply = () -> new KeyStoreResult.Stored(2);
        assertEquals("answered", keys().status(), "повтор сразу проходит");
        assertEquals(2, keyStore.orders.size());
    }

    @Test
    void theKeyStoreRefusingTheKeysByContentLetsTheSellerFixTheAnswerInsideTheWindow() {
        keyStore.reply = () -> new KeyStoreResult.Refused(422, List.of(Map.of("pointer", "/keys/1", "code", "not_unique")));

        ProblemException e = refused(() -> keys());

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(List.of(Map.of("pointer", "/keys/1", "code", "not_unique")), e.extensions().get("errors"));
        assertNull(requests.row(DELIVERY).leaseUntil);
        assertEquals("requested", requests.row(DELIVERY).status);
    }

    @Test
    void aReservationThatIsNotUsedAnymoreGives409AndLeavesTheRequestOpen() {
        keyStore.reply = () -> new KeyStoreResult.Refused(409, List.of());

        ProblemException e = refused(() -> keys());

        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("requested", requests.row(DELIVERY).status);
        assertNull(requests.row(DELIVERY).leaseUntil);
    }

    @Test
    void anUnexpectedRefusalOfTheKeyStoreIsAnAnomalyAndTheSellerIsToldToRepeat() {
        keyStore.reply = () -> new KeyStoreResult.Refused(404, List.of());

        ProblemException e = refused(() -> keys());

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals(1.0, meters.counter("delivery_anomalies_total", "reason", "seller_keys_refused").count());
        assertNull(requests.row(DELIVERY).leaseUntil);
    }

    @Test
    void theSameKeysAgainGiveTheSameResultWithoutASecondTransition() {
        keys();
        transaction.published().clear();

        SellerAnswerService.Result again = keys();

        assertEquals(new SellerAnswerService.Result(REQUEST, "answered", 2), again);
        assertEquals(2, keyStore.orders.size(), "хранилище сверяет значения по HMAC и возвращает те же ключи");
        assertEquals("answered", requests.row(DELIVERY).status);
        assertEquals(1.0, meters.counter("delivery_seller_requests_total", "outcome", "answered").count(), "переход один");
    }

    @Test
    void otherKeysForAnAnsweredRequestGet409RequestAlreadyAnswered() {
        keys();
        keyStore.reply = () -> new KeyStoreResult.Refused(409, List.of());

        ProblemException e = refused(() -> service.answer(SELLER, REQUEST, new Answer.Keys(List.of("OTHER-1", "OTHER-2"))));

        assertEquals(ProblemType.REQUEST_ALREADY_ANSWERED, e.type());
        assertEquals("answered", requests.row(DELIVERY).status);
    }

    @Test
    void aRequestOfAnotherSellerIsNotFoundLikeAnUnknownOne() {
        ProblemException foreign = refused(() -> service.answer(OTHER_SELLER, REQUEST, new Answer.Keys(KEYS)));
        ProblemException unknown = refused(() -> service.answer(SELLER, UUID.fromString("0199e0a0-0000-7000-8000-000000000bff"), new Answer.Keys(KEYS)));

        assertEquals(ProblemType.NOT_FOUND, foreign.type());
        assertEquals(ProblemType.NOT_FOUND, unknown.type());
        assertEquals(foreign.getMessage(), unknown.getMessage(), "ответ один и тот же: чужой запрос не отличить от несуществующего");
        assertTrue(keyStore.orders.isEmpty());
        assertEquals("requested", requests.row(DELIVERY).status);
    }

    @Test
    void anAnswerWhileAnotherAnswerHoldsTheLeaseGets409RequestInProgress() {
        requests.lease(REQUEST, clock.instant(), clock.instant().plusSeconds(30));

        ProblemException e = refused(() -> keys());

        assertEquals(ProblemType.REQUEST_IN_PROGRESS, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertTrue(keyStore.orders.isEmpty(), "ключи одним ответом не записываются дважды");
        assertEquals(clock.instant().plusSeconds(30), requests.row(DELIVERY).leaseUntil, "чужую аренду ответ не снимает");
    }

    @Test
    void theRefusalOfTheSellerClosesTheRequestAndCancelsTheOrderWithOneEvent() {
        SellerAnswerService.Result result = service.answer(SELLER, REQUEST, new Answer.Refusal("out_of_stock", "Нет на складе"));

        assertEquals(new SellerAnswerService.Result(REQUEST, "failed", null), result);
        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("failed", row.status);
        assertEquals("out_of_stock", row.errorCode);
        assertEquals("failed", deliveries.row(DELIVERY).status);
        assertEquals("seller_unavailable", deliveries.row(DELIVERY).failReason);
        assertEquals(1, transaction.published(DeliveryEvents.SELLER_TIMEOUT).size());
        assertEquals("seller_error", transaction.published(DeliveryEvents.SELLER_TIMEOUT).get(0).data().get("reason"));
        assertTrue(transaction.published(DeliveryEvents.FAILED).isEmpty());
        assertTrue(keyStore.orders.isEmpty(), "ключи не передавались");
        assertNull(row.leaseUntil);
    }

    @Test
    void anUnknownRefusalCodeIsRecordedAsOther() {
        service.answer(SELLER, REQUEST, new Answer.Refusal("weird_reason", null));

        assertEquals("other", requests.row(DELIVERY).errorCode);
    }

    @Test
    void theSameRefusalAgainGivesTheSameResultAndNoSecondEvent() {
        service.answer(SELLER, REQUEST, new Answer.Refusal("cannot_issue", null));

        SellerAnswerService.Result again = service.answer(SELLER, REQUEST, new Answer.Refusal("cannot_issue", null));

        assertEquals("failed", again.status());
        assertEquals(1, transaction.published(DeliveryEvents.SELLER_TIMEOUT).size());
    }

    @Test
    void keysAfterARefusalAndARefusalAfterKeysAreBothTooLate() {
        service.answer(SELLER, REQUEST, new Answer.Refusal("cannot_issue", null));
        assertEquals(ProblemType.REQUEST_ALREADY_ANSWERED, refused(() -> keys()).type());

        UUID second = UUID.fromString("0199e0a0-0000-7000-8000-000000000a02");
        UUID secondRequest = UUID.fromString("0199e0a0-0000-7000-8000-000000000b02");
        deliveries.insertPrimary(second, UUID.fromString("0199e0a0-0000-7000-8000-000000000302"), BUYER, "anna@mail.example", "Игра", 2, T0);
        requests.insert(second, secondRequest, new SellerRequestSeed(SELLER, PRODUCT, 1043), T0.plus(WINDOW), T0);
        service.answer(SELLER, secondRequest, new Answer.Keys(KEYS));

        assertEquals(ProblemType.REQUEST_ALREADY_ANSWERED, refused(() -> service.answer(SELLER, secondRequest, new Answer.Refusal("other", null))).type());
    }

    @Test
    void aRefusalAfterTheWindowGets409AndChangesNothing() {
        clock.advance(WINDOW.plusSeconds(1));

        ProblemException e = refused(() -> service.answer(SELLER, REQUEST, new Answer.Refusal("out_of_stock", null)));

        assertEquals(ProblemType.REQUEST_EXPIRED, e.type());
        assertEquals("requested", requests.row(DELIVERY).status);
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void aRequestClosedByThePlatformWithoutAnAnswerOfTheSellerIsExpiredForTheSeller() {
        UUID endpointless = UUID.fromString("0199e0a0-0000-7000-8000-000000000a03");
        UUID request = UUID.fromString("0199e0a0-0000-7000-8000-000000000b03");
        deliveries.insertPrimary(endpointless, UUID.fromString("0199e0a0-0000-7000-8000-000000000303"), BUYER, "anna@mail.example", "Игра", 1, T0);
        requests.insert(endpointless, request, new SellerRequestSeed(SELLER, PRODUCT, 1044), T0.plus(WINDOW), T0);
        requests.fail(endpointless, null, "integration_missing", null);

        ProblemException e = refused(() -> service.answer(SELLER, request, new Answer.Keys(KEYS)));

        assertEquals(ProblemType.REQUEST_EXPIRED, e.type());
    }
}
