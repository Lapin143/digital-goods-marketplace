package dgm.platform.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.DependencyRejectedException;
import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.crypto.SellerSecrets;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import dgm.platform.notification.client.SellerEndpoints;
import dgm.platform.notification.client.WebhookCalls;
import dgm.platform.notification.domain.NewWebhookDelivery;
import dgm.platform.notification.domain.PendingWebhook;
import dgm.platform.notification.domain.RetryPolicy;
import dgm.platform.notification.domain.SellerEndpoint;
import dgm.platform.notification.domain.WebhookCallResult;
import dgm.platform.notification.testing.InMemoryWebhookDeliveries;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Отправитель webhook продавцам (Р-193): подписанная отправка (TC-970), два сбоя и успех при одном идентификаторе события (TC-971), шесть сбоев и статус
 * {@code failed} (TC-972), отсутствующее или отключённое подключение и смена секрета (TC-973). Сеть и база заменены двойниками, время ведут часы теста.
 */
class WebhookDispatcherTest {

    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID OTHER_SELLER = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");
    private static final SellerSecrets SECRETS = new SellerSecrets(SecretValue.of("master-secret-for-tests-0123456789"));
    private static final List<Duration> LADDER = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
            Duration.ofHours(2));

    /** Подключения продавцов: что записано, то и отдаётся; недоступность каталога включается флагом. */
    private static final class Endpoints implements SellerEndpoints {
        final Map<UUID, SellerEndpoint> known = new HashMap<>();
        RuntimeException failure;

        @Override
        public Optional<SellerEndpoint> find(UUID sellerId) {
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(known.get(sellerId));
        }
    }

    private record Call(String url, String secret, UUID eventId, String body, Instant at) {
    }

    /** Получатель: отвечает по очереди заготовленных исходов, а когда она пуста, принимает с кодом 200. Секрет читается в момент вызова, до уничтожения. */
    private final class Receiver implements WebhookCalls {
        final Deque<WebhookCallResult> script = new ArrayDeque<>();
        final List<Call> calls = new ArrayList<>();

        @Override
        public WebhookCallResult send(String url, SecretValue secret, PendingWebhook webhook) {
            calls.add(new Call(url, secret.reveal(), webhook.eventId(), webhook.payload(), clock.instant()));
            return script.isEmpty() ? WebhookCallResult.ok(200) : script.poll();
        }
    }

    private ManualClock clock;
    private InMemoryWebhookDeliveries queue;
    private Endpoints endpoints;
    private Receiver receiver;
    private WebhookDispatcher dispatcher;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        queue = new InMemoryWebhookDeliveries();
        endpoints = new Endpoints();
        endpoints.known.put(SELLER, new SellerEndpoint("https://shop.seller.example/dgm/webhook", "active", 1));
        receiver = new Receiver();
        meters = new SimpleMeterRegistry();
        dispatcher = new WebhookDispatcher(queue, endpoints, receiver, SECRETS, new RetryPolicy(LADDER), Duration.ofMinutes(2), 10, clock, meters);
    }

    private int sequence;

    /** Идентификаторы доставок по порядку постановки: очередь берёт уведомления с равным сроком по идентификатору, случайный порядок делал бы тест нестабильным. */
    private UUID nextId() {
        return new UUID(0x0199e0a000007000L, 0x8000000000000000L | ++sequence);
    }

    private UUID enqueue(UUID seller, String type) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("type", type);
        queue.insert(new NewWebhookDelivery(nextId(), seller, eventId, type, 1042, payload, clock.instant()));
        return eventId;
    }

    private double count(String result) {
        return meters.counter("webhooks_total", "result", result).count();
    }

    @Test
    @Tag("TC-970")
    void aNotificationIsSentAtOnceSignedWithTheSecretOfTheSellerAndBecomesSent() {
        UUID eventId = enqueue(SELLER, "order.paid");

        assertEquals(1, dispatcher.runOnce());

        assertEquals(1, receiver.calls.size());
        Call call = receiver.calls.get(0);
        assertEquals("https://shop.seller.example/dgm/webhook", call.url());
        assertEquals(eventId, call.eventId());
        assertEquals(SECRETS.secret(SELLER, 1).reveal(), call.secret(), "подписывается секретом этого продавца и этой версии (Р-187)");
        InMemoryWebhookDeliveries.Row row = queue.only();
        assertEquals("sent", row.status);
        assertEquals(1, row.attempts);
        assertEquals(200, row.lastResponseCode);
        assertNull(row.lastErrorCode);
        assertNull(row.nextAttemptAt, "срок есть только у уведомления в очереди");
        assertEquals(T0, row.sentAt);
        assertEquals(1.0, count("sent"));
        assertEquals(0, dispatcher.runOnce(), "отправленное повторно не берётся: повтор безвреден");
        assertEquals(1, receiver.calls.size());
    }

    @Test
    @Tag("TC-970")
    void aNotificationThatIsNotDueYetIsNotTaken() {
        enqueue(SELLER, "order.issued");
        clock.set(T0.minusSeconds(1));

        assertEquals(0, dispatcher.runOnce());
        assertTrue(receiver.calls.isEmpty());
    }

    @Test
    @Tag("TC-971")
    void twoFailuresAndASuccessTakeThreeAttemptsWithTheSameEventIdAndTheBodyUnchanged() {
        UUID eventId = enqueue(SELLER, "order.issued");
        receiver.script.add(WebhookCallResult.failed(503, "seller_http_503"));
        receiver.script.add(WebhookCallResult.failed(null, "seller_timeout"));

        dispatcher.runOnce();
        InMemoryWebhookDeliveries.Row row = queue.only();
        assertEquals("queued", row.status);
        assertEquals(1, row.attempts);
        assertEquals(T0.plusSeconds(30), row.nextAttemptAt, "после первой неудачи пауза 30 секунд");
        assertEquals(503, row.lastResponseCode);
        assertEquals("seller_http_503", row.lastErrorCode);

        assertEquals(0, dispatcher.runOnce(), "пауза не истекла");
        clock.advance(Duration.ofSeconds(30));
        dispatcher.runOnce();
        assertEquals(2, row.attempts);
        assertEquals(T0.plusSeconds(30).plus(Duration.ofMinutes(2)), row.nextAttemptAt, "после второй пауза 2 минуты");
        assertNull(row.lastResponseCode, "ответа не было: тайм-аут");
        assertEquals("seller_timeout", row.lastErrorCode);

        clock.advance(Duration.ofMinutes(2));
        dispatcher.runOnce();

        assertEquals("sent", row.status);
        assertEquals(3, row.attempts);
        assertEquals(3, receiver.calls.size());
        assertTrue(receiver.calls.stream().allMatch(c -> c.eventId().equals(eventId)), "идентификатор события во всех попытках один (X-Event-Id)");
        assertEquals(1, receiver.calls.stream().map(Call::body).distinct().count(), "тело во всех попытках одно и то же");
        assertEquals(2.0, count("retry"));
        assertEquals(1.0, count("sent"));
    }

    @Test
    @Tag("TC-972")
    void sixFailuresGiveTheStatusFailedWithTheWholeLadderOfPauses() {
        enqueue(SELLER, "order.refunded");
        for (int i = 0; i < 6; i++) {
            receiver.script.add(WebhookCallResult.failed(500, "seller_http_500"));
        }
        List<Duration> pauses = new ArrayList<>();
        InMemoryWebhookDeliveries.Row row = queue.only();

        for (int attempt = 1; attempt <= 6; attempt++) {
            assertEquals(1, dispatcher.runOnce(), "попытка " + attempt);
            assertEquals(attempt, row.attempts);
            if (attempt < 6) {
                assertEquals("queued", row.status, "до шестой неудачи уведомление не теряется");
                Duration pause = Duration.between(clock.instant(), row.nextAttemptAt);
                pauses.add(pause);
                clock.advance(pause);
            }
        }

        assertEquals(LADDER, pauses, "паузы 30 секунд, 2, 10, 30 минут, 2 часа (Р-193)");
        assertEquals("failed", row.status);
        assertEquals(6, row.attempts);
        assertEquals(500, row.lastResponseCode);
        assertEquals("seller_http_500", row.lastErrorCode);
        assertNull(row.nextAttemptAt);
        assertEquals(5.0, count("retry"));
        assertEquals(1.0, count("failed"));
        clock.advance(Duration.ofDays(1));
        assertEquals(0, dispatcher.runOnce(), "ручного повтора нет, платформа сама больше не пытается");
        assertEquals(6, receiver.calls.size());
    }

    @Test
    @Tag("TC-972")
    void aClientErrorOfTheReceiverIsAFailureOfTheAttemptToo() {
        enqueue(SELLER, "order.cancelled");
        receiver.script.add(WebhookCallResult.failed(404, "seller_http_404"));

        dispatcher.runOnce();

        InMemoryWebhookDeliveries.Row row = queue.only();
        assertEquals("queued", row.status, "отказ 4xx не окончателен: получатель мог ошибиться временно");
        assertEquals(404, row.lastResponseCode);
        assertEquals(T0.plusSeconds(30), row.nextAttemptAt);
    }

    @Test
    @Tag("TC-973")
    void aSellerWithoutAConnectionOrWithADisabledOneGetsTheStatusSkippedAndNothingIsSent() {
        UUID noConnection = UUID.randomUUID();
        enqueue(noConnection, "order.paid");
        endpoints.known.put(OTHER_SELLER, new SellerEndpoint("https://off.seller.example/dgm", "disabled", 3));
        enqueue(OTHER_SELLER, "order.paid");

        assertEquals(2, dispatcher.runOnce());

        assertTrue(receiver.calls.isEmpty(), "в систему без действующего подключения ничего не уходит");
        Map<UUID, InMemoryWebhookDeliveries.Row> bySeller = new HashMap<>();
        queue.rows().forEach(r -> bySeller.put(r.sellerId, r));
        assertEquals("skipped", bySeller.get(noConnection).status);
        assertEquals("integration_missing", bySeller.get(noConnection).lastErrorCode);
        assertEquals("skipped", bySeller.get(OTHER_SELLER).status);
        assertEquals("integration_disabled", bySeller.get(OTHER_SELLER).lastErrorCode);
        assertEquals(0, bySeller.get(OTHER_SELLER).attempts);
        assertEquals(2.0, count("skipped"));
    }

    @Test
    @Tag("TC-973")
    void afterTheSecretIsChangedTheNextNotificationIsSignedWithTheNewVersion() {
        enqueue(SELLER, "order.paid");
        dispatcher.runOnce();
        endpoints.known.put(SELLER, new SellerEndpoint("https://shop.seller.example/dgm/webhook", "active", 2));
        enqueue(SELLER, "order.issued");

        dispatcher.runOnce();

        assertEquals(2, receiver.calls.size());
        assertEquals(SECRETS.secret(SELLER, 1).reveal(), receiver.calls.get(0).secret());
        assertEquals(SECRETS.secret(SELLER, 2).reveal(), receiver.calls.get(1).secret(), "новая версия секрета, новая подпись");
        assertNotEquals(receiver.calls.get(0).secret(), receiver.calls.get(1).secret());
    }

    @Test
    @Tag("TC-973")
    void theSecretOfAnotherSellerNeverSignsTheNotification() {
        endpoints.known.put(OTHER_SELLER, new SellerEndpoint("https://other.seller.example/dgm", "active", 1));
        enqueue(SELLER, "order.paid");
        enqueue(OTHER_SELLER, "order.paid");

        dispatcher.runOnce();

        assertEquals(2, receiver.calls.size());
        Call first = receiver.calls.stream().filter(c -> c.url().contains("shop.seller")).findFirst().orElseThrow();
        Call second = receiver.calls.stream().filter(c -> c.url().contains("other.seller")).findFirst().orElseThrow();
        assertEquals(SECRETS.secret(SELLER, 1).reveal(), first.secret());
        assertEquals(SECRETS.secret(OTHER_SELLER, 1).reveal(), second.secret());
        assertNotEquals(first.secret(), second.secret());
    }

    @Test
    @Tag("TC-971")
    void whenTheCatalogIsUnavailableTheAttemptIsNotSpentAndTheNotificationComesBackAfterTheLease() {
        enqueue(SELLER, "order.paid");
        endpoints.failure = new DependencyUnavailableException("catalog-service", DependencyUnavailableException.TIMEOUT, 0, null);

        assertEquals(1, dispatcher.runOnce());

        InMemoryWebhookDeliveries.Row row = queue.only();
        assertEquals("queued", row.status);
        assertEquals(0, row.attempts, "сбой каталога не тратит попытку");
        assertTrue(receiver.calls.isEmpty());
        assertEquals(T0.plus(Duration.ofMinutes(2)), row.nextAttemptAt, "аренда сдвинула срок: другой экземпляр не возьмёт уведомление раньше");
        assertEquals(0, dispatcher.runOnce());

        endpoints.failure = null;
        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, dispatcher.runOnce());
        assertEquals("sent", row.status);
    }

    @Test
    @Tag("TC-971")
    void aRefusalOfTheCatalogAlsoLeavesTheNotificationForLater() {
        enqueue(SELLER, "order.paid");
        endpoints.failure = new DependencyRejectedException("catalog-service", 403, "forbidden");

        dispatcher.runOnce();

        assertEquals(0, queue.only().attempts);
        assertEquals("queued", queue.only().status);
    }

    @Test
    @Tag("TC-972")
    void aFailureOfOneReceiverDoesNotStopTheOthers() {
        endpoints.known.put(OTHER_SELLER, new SellerEndpoint("https://other.seller.example/dgm", "active", 1));
        UUID slow = enqueue(SELLER, "order.paid");
        UUID fine = enqueue(OTHER_SELLER, "order.paid");
        receiver.script.add(WebhookCallResult.failed(null, "seller_timeout"));

        assertEquals(2, dispatcher.runOnce());

        Map<UUID, InMemoryWebhookDeliveries.Row> byEvent = new HashMap<>();
        queue.rows().forEach(r -> byEvent.put(r.eventId, r));
        assertEquals("queued", byEvent.get(slow).status);
        assertEquals("sent", byEvent.get(fine).status, "исправный получатель не ждёт неисправного сверх его тайм-аута");
    }

    @Test
    @Tag("TC-971")
    void anUnexpectedErrorLeavesTheNotificationInTheQueueUntilTheLeaseExpires() {
        enqueue(SELLER, "order.paid");
        WebhookCalls broken = (url, secret, webhook) -> {
            throw new IllegalStateException("сбой клиента");
        };
        WebhookDispatcher failing = new WebhookDispatcher(queue, endpoints, broken, SECRETS, new RetryPolicy(LADDER), Duration.ofMinutes(2), 10, clock, meters);

        assertEquals(1, failing.runOnce(), "ошибка одного уведомления не роняет проход");

        assertEquals("queued", queue.only().status);
        assertEquals(0, queue.only().attempts);
        assertFalse(queue.only().nextAttemptAt.isBefore(T0.plus(Duration.ofMinutes(2))));
    }
}
