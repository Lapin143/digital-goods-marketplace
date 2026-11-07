package dgm.delivery.delivery.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeSellerRequests;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.client.SellerCalls;
import dgm.delivery.delivery.client.SellerEndpoints;
import dgm.delivery.delivery.domain.PendingRequest;
import dgm.delivery.delivery.domain.SellerCallResult;
import dgm.delivery.delivery.domain.SellerEndpoint;
import dgm.delivery.delivery.domain.SellerRequestSeed;
import dgm.delivery.delivery.service.DeliveryEvents;
import dgm.delivery.delivery.service.SellerRequestOutcomes;
import dgm.delivery.delivery.service.SellerSettings;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Задание отправки запросов продавцам (TC-963, TC-966, ADR-028): адрес и версия секрета берутся у каталога, секрет считается из мастер-секрета, исход
 * отправки записывается по правилам; продавец не подключён или отключён: запрос закрывается без отправки; каталог недоступен: попытка не тратится;
 * запрос после конца окна не отправляется; повторный проход запрос в аренде не берёт.
 */
@Tag("TC-963")
@Tag("TC-966")
class SellerRequestJobTest {

    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
    private static final UUID DELIVERY = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final UUID REQUEST = UUID.fromString("0199e0a0-0000-7000-8000-000000000b01");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final SellerSecrets SECRETS = new SellerSecrets(SecretValue.of("master-secret-for-tests-0123456789"));

    /** Вызов продавца без сети: запоминает, что и с каким секретом отправлено. */
    private static final class Calls implements SellerCalls {
        final List<String> urls = new ArrayList<>();
        final List<String> secrets = new ArrayList<>();
        final List<PendingRequest> requests = new ArrayList<>();
        SellerCallResult result = new SellerCallResult.Accepted();

        @Override
        public SellerCallResult send(String url, SecretValue secret, PendingRequest request) {
            urls.add(url);
            secrets.add(secret.reveal());
            requests.add(request);
            return result;
        }
    }

    private FakeDeliveries deliveries;
    private FakeSellerRequests requests;
    private FakeTransaction transaction;
    private ManualClock clock;
    private Calls calls;
    private Optional<SellerEndpoint> endpoint;
    private RuntimeException catalogFailure;
    private int catalogCalls;
    private SellerRequestJob job;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        requests = new FakeSellerRequests(deliveries);
        transaction = new FakeTransaction();
        clock = new ManualClock(T0);
        calls = new Calls();
        endpoint = Optional.of(new SellerEndpoint("https://shop.seller.example/dgm", "active", 3));
        SellerSettings settings = SellerSettings.defaults();
        SellerEndpoints endpoints = sellerId -> {
            catalogCalls++;
            if (catalogFailure != null) {
                throw catalogFailure;
            }
            assertEquals(SELLER, sellerId);
            return endpoint;
        };
        SellerRequestOutcomes outcomes = new SellerRequestOutcomes(requests, deliveries, transaction, settings, clock, new SimpleMeterRegistry());
        job = new SellerRequestJob(requests, endpoints, calls, SECRETS, outcomes, settings, clock);
        deliveries.insertPrimary(DELIVERY, ORDER, BUYER, "anna@mail.example", "Игра", 2, T0);
        deliveries.insertWatch(ORDER, T0.plus(Duration.ofMinutes(30)), T0);
        requests.insert(DELIVERY, REQUEST, new SellerRequestSeed(SELLER, PRODUCT, 1042), T0.plus(WINDOW), T0);
    }

    @Test
    void theRequestIsSentToTheAddressOfTheSellerWithTheSecretOfTheVersionFromTheCatalog() {
        assertEquals(1, job.runOnce());

        assertEquals(List.of("https://shop.seller.example/dgm"), calls.urls);
        assertEquals(List.of(SECRETS.secret(SELLER, 3).reveal()), calls.secrets, "секрет считается по версии из каталога");
        PendingRequest sent = calls.requests.get(0);
        assertEquals(REQUEST, sent.requestId());
        assertEquals(ORDER, sent.orderId());
        assertEquals(2, sent.quantity(), "количество ключей берётся из выдачи");
        assertEquals(1042, sent.orderNumber());
        assertEquals("accepted", requests.row(DELIVERY).status);
        assertEquals(1, requests.row(DELIVERY).attempts);
        assertTrue(transaction.published().isEmpty());
    }

    @Test
    void aServerErrorKeepsTheRequestForARepeatAfterFiveSeconds() {
        calls.result = new SellerCallResult.Retryable("seller_http_503");

        job.runOnce();

        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("requested", row.status);
        assertEquals(1, row.attempts);
        assertEquals(T0.plusSeconds(5), row.nextAttemptAt);
        assertEquals(0, job.runOnce(), "до срока повтора проход ничего не берёт");

        clock.advance(Duration.ofSeconds(5));
        assertEquals(1, job.runOnce());
        assertEquals(2, calls.urls.size());
    }

    @Test
    void threeFailedSendsInsideTheWindowAreTheLastAndTheRequestWaitsForTheTimeout() {
        calls.result = new SellerCallResult.Retryable("seller_io");

        job.runOnce();
        clock.advance(Duration.ofSeconds(5));
        job.runOnce();
        clock.advance(Duration.ofSeconds(30));
        job.runOnce();
        clock.advance(Duration.ofMinutes(5));

        assertEquals(0, job.runOnce(), "четвёртой отправки нет");
        assertEquals(3, calls.urls.size());
        assertEquals(3, requests.row(DELIVERY).attempts);
        assertEquals("requested", requests.row(DELIVERY).status);
    }

    @Test
    void aClientErrorIsARefusalOfTheSellerAndTheOrderGoesTheErrorPathAtOnce() {
        calls.result = new SellerCallResult.Rejected(422);

        job.runOnce();

        assertEquals("failed", requests.row(DELIVERY).status);
        assertEquals("seller_rejected", requests.row(DELIVERY).errorCode);
        assertEquals("failed", deliveries.row(DELIVERY).status);
        assertEquals("seller_unavailable", deliveries.row(DELIVERY).failReason);
        assertEquals(1, transaction.published(DeliveryEvents.SELLER_TIMEOUT).size());
        assertEquals("seller_error", transaction.published(DeliveryEvents.SELLER_TIMEOUT).get(0).data().get("reason"));
    }

    @Test
    void aSellerWithoutAnIntegrationIsNotCalledAndTheRequestIsClosedWithIntegrationMissing() {
        endpoint = Optional.empty();

        job.runOnce();

        assertTrue(calls.urls.isEmpty(), "подключения нет: запрос не отправляется");
        assertEquals("failed", requests.row(DELIVERY).status);
        assertEquals("integration_missing", requests.row(DELIVERY).errorCode);
        assertEquals(0, requests.row(DELIVERY).attempts);
        assertEquals(1, transaction.published(DeliveryEvents.SELLER_TIMEOUT).size());
    }

    @Test
    void aDisabledIntegrationIsNotCalledEither() {
        endpoint = Optional.of(new SellerEndpoint("https://shop.seller.example/dgm", "disabled", 1));

        job.runOnce();

        assertTrue(calls.urls.isEmpty());
        assertEquals("integration_disabled", requests.row(DELIVERY).errorCode);
        assertEquals("failed", deliveries.row(DELIVERY).status);
    }

    @Test
    void anAddressThatFailedTheGuardClosesTheRequestWithoutCountingASend() {
        calls.result = new SellerCallResult.EndpointRejected();

        job.runOnce();

        assertEquals("endpoint_rejected", requests.row(DELIVERY).errorCode);
        assertEquals(0, requests.row(DELIVERY).attempts, "соединение не открывалось");
        assertEquals("failed", deliveries.row(DELIVERY).status);
    }

    @Test
    void whenTheCatalogIsUnavailableTheAttemptIsNotSpentAndTheRequestReturnsAfterTheLease() {
        catalogFailure = new DependencyUnavailableException("catalog-service", DependencyUnavailableException.TIMEOUT, 0, null);

        assertEquals(1, job.runOnce());

        FakeSellerRequests.Row row = requests.row(DELIVERY);
        assertEquals("requested", row.status);
        assertEquals(0, row.attempts, "продавцу ничего не отправлялось: попытка не потрачена");
        assertTrue(calls.urls.isEmpty());
        assertEquals(0, job.runOnce(), "запрос в аренде, другой проход его не берёт");

        catalogFailure = null;
        clock.advance(SellerSettings.defaults().sendLease());
        assertEquals(1, job.runOnce());
        assertEquals("accepted", row.status);
    }

    @Test
    void aRequestWhoseWindowIsClosedIsNotSentAndIsLeftToTheTimeout() {
        clock.advance(WINDOW);

        assertEquals(1, job.runOnce());

        assertTrue(calls.urls.isEmpty(), "окно вышло: отправлять незачем");
        assertEquals(0, catalogCalls, "каталог тоже не спрашивается");
        assertEquals("requested", requests.row(DELIVERY).status);
        assertNull(requests.row(DELIVERY).errorCode);
    }

    @Test
    void anErrorOfOneRequestDoesNotStopTheOthers() {
        UUID second = UUID.fromString("0199e0a0-0000-7000-8000-000000000a02");
        deliveries.insertPrimary(second, UUID.fromString("0199e0a0-0000-7000-8000-000000000302"), BUYER, "anna@mail.example", "Игра", 1, T0);
        requests.insert(second, UUID.fromString("0199e0a0-0000-7000-8000-000000000b02"), new SellerRequestSeed(SELLER, PRODUCT, 1043), T0.plus(WINDOW), T0);
        SellerCalls broken = (url, secret, request) -> {
            if (request.deliveryId().equals(DELIVERY)) {
                throw new IllegalStateException("сбой");
            }
            return new SellerCallResult.Accepted();
        };
        SellerSettings settings = SellerSettings.defaults();
        SellerRequestOutcomes outcomes = new SellerRequestOutcomes(requests, deliveries, transaction, settings, clock, new SimpleMeterRegistry());
        job = new SellerRequestJob(requests, sellerId -> endpoint, broken, SECRETS, outcomes, settings, clock);

        assertEquals(2, job.runOnce());

        assertEquals("requested", requests.row(DELIVERY).status, "исход не записан: запрос вернётся после аренды");
        assertEquals("accepted", requests.row(second).status);
    }
}
