package dgm.delivery.delivery.poller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.client.StatusClient;
import dgm.delivery.delivery.domain.ProviderLookup;
import dgm.delivery.delivery.domain.ProviderStatus;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.delivery.delivery.service.DeliveryStatuses;
import dgm.delivery.delivery.service.StatusSettings;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Опрос статусов писем (ADR-011, TC-607): потерянный вебхук закрывается опросом, ответ применяется тем же кодом, что вебхук; молодые выдачи не
 * спрашиваются; ответы «нет статуса», «не знаю» и «не ответил» ничего не меняют; выдачи идут по кругу порциями.
 */
@Tag("TC-607")
class StatusPollerTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    private FakeDeliveries deliveries;
    private FakeTransaction transaction;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private final Map<UUID, ProviderLookup> answers = new HashMap<>();
    private final List<UUID> asked = new ArrayList<>();
    private RuntimeException failure;
    private StatusPoller poller;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        transaction = new FakeTransaction();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        build(new StatusSettings(Duration.ofMinutes(5), 50, 50));
    }

    private void build(StatusSettings settings) {
        StatusClient provider = messageId -> {
            asked.add(messageId);
            if (failure != null) {
                throw failure;
            }
            return answers.getOrDefault(messageId, new ProviderLookup.Known(ProviderStatus.ACCEPTED, null));
        };
        DeliveryStatuses statuses = new DeliveryStatuses(deliveries, transaction, DeliverySettings.defaults(), clock, meters);
        poller = new StatusPoller(deliveries, provider, statuses, settings, clock, meters);
    }

    private static UUID id(int number) {
        return UUID.fromString("0199e0a0-0000-7000-8000-00000000" + String.format("%04x", number));
    }

    /** Выдача, которую провайдер принял {@code ago} назад. */
    private UUID sent(int number, Duration ago) {
        UUID order = UUID.fromString("0199e0a0-0000-7000-8000-00000001" + String.format("%04x", number));
        deliveries.addSent(id(number), order, BUYER, "anna@mail.example", "Игра", 1, 1, T0.minus(ago));
        deliveries.insertWatch(order, T0.plus(Duration.ofMinutes(25)), T0.minus(ago));
        return id(number);
    }

    @Test
    void aYoungDeliveryIsNotAskedAboutAndAnOldEnoughOneIs() {
        sent(1, Duration.ofMinutes(4).plusSeconds(59));
        sent(2, Duration.ofMinutes(5));

        assertEquals(0, poller.runOnce());

        assertEquals(List.of(id(2)), asked);
    }

    @Test
    void aDeliveredAnswerClosesTheDeliveryLikeAWebhookWouldHave() {
        UUID delivery = sent(1, Duration.ofMinutes(6));
        answers.put(delivery, new ProviderLookup.Known(ProviderStatus.DELIVERED, null));

        assertEquals(1, poller.runOnce());

        assertEquals("delivered", deliveries.row(delivery).status);
        assertEquals(T0, deliveries.row(delivery).deliveredAt, "времени провайдера в ответе нет, берётся время опроса");
        assertEquals(1, transaction.published("delivery.delivered").size());
        assertEquals(0, poller.runOnce(), "закрытая выдача больше не опрашивается");
        assertEquals(1, asked.size());
        assertEquals(1, meters.counter("delivery_status_polls_total", "result", "delivered").count());
    }

    @Test
    void aFailedAnswerFailsTheDeliveryWithTheReasonOfTheProvider() {
        UUID delivery = sent(1, Duration.ofMinutes(6));
        answers.put(delivery, new ProviderLookup.Known(ProviderStatus.FAILED, "address_rejected"));

        assertEquals(1, poller.runOnce());

        assertEquals("failed", deliveries.row(delivery).status);
        assertEquals("address_rejected", deliveries.row(delivery).failReason);
        assertEquals(1, transaction.published("delivery.failed").size());
    }

    @Test
    void anAcceptedAnswerAnUnknownMessageAndASilentProviderChangeNothingAndTheDeliveryIsAskedAgainNextTime() {
        UUID accepted = sent(1, Duration.ofMinutes(6));
        UUID unknown = sent(2, Duration.ofMinutes(6));
        UUID silent = sent(3, Duration.ofMinutes(6));
        answers.put(unknown, new ProviderLookup.Unknown());
        answers.put(silent, new ProviderLookup.Unavailable("provider_timeout"));

        assertEquals(0, poller.runOnce());
        assertEquals(0, poller.runOnce());

        assertEquals("sent", deliveries.row(accepted).status);
        assertEquals("sent", deliveries.row(unknown).status);
        assertEquals("sent", deliveries.row(silent).status);
        assertEquals(0, transaction.published().size());
        assertTrue(deliveries.providerStatuses().isEmpty(), "статус accepted опрос не записывает");
        assertEquals(6, asked.size());
        assertEquals(2, meters.counter("delivery_status_polls_total", "result", "unknown").count());
        assertEquals(2, meters.counter("delivery_status_polls_total", "result", "unavailable").count());
        assertEquals(2, meters.counter("delivery_status_polls_total", "result", "accepted").count());
    }

    @Test
    void anExceptionOfTheClientDoesNotStopThePassForTheOthers() {
        UUID first = sent(1, Duration.ofMinutes(7));
        UUID second = sent(2, Duration.ofMinutes(6));
        answers.put(second, new ProviderLookup.Known(ProviderStatus.DELIVERED, null));
        failure = new IllegalStateException("обрыв");

        assertEquals(0, poller.runOnce());

        failure = null;
        assertEquals(1, poller.runOnce());
        assertEquals("sent", deliveries.row(first).status);
        assertEquals("delivered", deliveries.row(second).status);
        assertEquals(2, meters.counter("delivery_status_polls_total", "result", "error").count());
    }

    @Test
    void deliveriesStuckWithoutAStatusAreAskedAboutRoundTheCircleInPortions() {
        build(new StatusSettings(Duration.ofMinutes(5), 2, 50));
        for (int number = 1; number <= 5; number++) {
            sent(number, Duration.ofMinutes(60 - number));
        }

        for (int pass = 0; pass < 4; pass++) {
            poller.runOnce();
        }

        assertEquals(List.of(id(1), id(2), id(3), id(4), id(5), id(1), id(2)), asked,
                "порции по две в порядке отправки: 1 2, 3 4, 5, потом круг начинается заново");
    }

    @Test
    void aDeliveryClosedBetweenPassesDoesNotBreakTheCircle() {
        build(new StatusSettings(Duration.ofMinutes(5), 2, 50));
        for (int number = 1; number <= 3; number++) {
            sent(number, Duration.ofMinutes(60 - number));
        }
        answers.put(id(1), new ProviderLookup.Known(ProviderStatus.DELIVERED, null));

        poller.runOnce();
        poller.runOnce();
        poller.runOnce();

        assertEquals(List.of(id(1), id(2), id(3), id(2), id(3)), asked);
        assertEquals("delivered", deliveries.row(id(1)).status);
    }
}
