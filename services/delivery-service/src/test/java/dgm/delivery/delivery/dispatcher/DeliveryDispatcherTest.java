package dgm.delivery.delivery.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.FakeDeliveries;
import dgm.delivery.delivery.FakeDeliveries.Row;
import dgm.delivery.delivery.FakeTransaction;
import dgm.delivery.delivery.client.EmailClient;
import dgm.delivery.delivery.client.KeyClient;
import dgm.delivery.delivery.client.KeyRequestException;
import dgm.delivery.delivery.domain.AttemptResult;
import dgm.delivery.delivery.domain.DeliveryAddress;
import dgm.delivery.delivery.domain.DeliverySchedule;
import dgm.delivery.delivery.domain.ReleasedKeys;
import dgm.delivery.delivery.letter.Letter;
import dgm.delivery.delivery.letter.LetterBuilder;
import dgm.delivery.delivery.service.DeliveryOutcomes;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Диспетчер отправки (ADR-011, TC-603 – TC-606): ключи, письмо и передача провайдеру вне транзакции, исход записывается после, значения ключей
 * стираются, число ключей сверяется с заказом (INV-19), обезличенный адрес не отправляется, сбой записи оставляет выдачу на аренде.
 */
class DeliveryDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-19T12:00:00.123Z");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final String ADDRESS = "anna@mail.example";

    /** Одно отправленное письмо: адресат, идентификатор сообщения и текст. */
    private record Sent(UUID messageId, String to, Letter letter) {
    }

    private FakeDeliveries deliveries;
    private FakeTransaction transaction;
    private ManualClock clock;
    private SimpleMeterRegistry meters;
    private DeliveryDispatcher dispatcher;
    private final List<UUID> keyRequests = new ArrayList<>();
    private final List<Sent> sent = new ArrayList<>();
    private final List<ReleasedKeys> issued = new ArrayList<>();
    private KeyClient keys;
    private EmailClient email;

    @BeforeEach
    void setUp() {
        deliveries = new FakeDeliveries();
        transaction = new FakeTransaction();
        clock = new ManualClock(T0);
        meters = new SimpleMeterRegistry();
        keys = orderId -> {
            keyRequests.add(orderId);
            ReleasedKeys released = new ReleasedKeys(List.of(SecretValue.of("AAAAA-11111"), SecretValue.of("BBBBB-22222")));
            issued.add(released);
            return released;
        };
        email = (messageId, to, letter) -> {
            sent.add(new Sent(messageId, to, letter));
            return new AttemptResult.Accepted();
        };
        build();
    }

    private void build() {
        DeliveryOutcomes outcomes = new DeliveryOutcomes(deliveries, transaction, DeliverySchedule.parse("10s,30s,2m,5m,10m", 0.2, new Random(3)), clock, meters);
        dispatcher = new DeliveryDispatcher(deliveries, keys, new LetterBuilder(), email, outcomes, DeliverySettings.defaults(), clock, meters);
    }

    private UUID add(int number, String address, int quantity) {
        UUID id = UUID.fromString("0199e0a0-0000-7000-8000-00000000" + String.format("%04x", number));
        UUID order = UUID.fromString("0199e0a0-0000-7000-8000-00000001" + String.format("%04x", number));
        deliveries.insertPrimary(id, order, BUYER, address, "Игра", quantity, T0);
        return id;
    }

    @Tag("TC-603")
    @Test
    void aDueDeliveryGetsItsKeysAnAssembledLetterAndAnAcceptedOutcome() {
        UUID id = add(1, ADDRESS, 2);

        assertEquals(1, dispatcher.runOnce());

        assertEquals(1, keyRequests.size());
        assertEquals(deliveries.row(id).orderId, keyRequests.get(0));
        assertEquals(1, sent.size());
        Sent mail = sent.get(0);
        assertEquals(id, mail.messageId(), "идентификатор сообщения у провайдера равен идентификатору выдачи");
        assertEquals(ADDRESS, mail.to());
        assertTrue(mail.letter().text().contains("1. AAAAA-11111") && mail.letter().text().contains("2. BBBBB-22222"), mail.letter().text());
        assertEquals("sent", deliveries.row(id).status);
        assertEquals(1, deliveries.row(id).attemptNo);
        assertEquals(1, transaction.published("delivery.accepted").size());
    }

    @Tag("TC-603")
    @Test
    void theValuesAreErasedAfterTheAttemptWhateverItsOutcome() {
        add(1, ADDRESS, 2);
        add(2, ADDRESS, 2);
        email = (messageId, to, letter) -> {
            if (sent.isEmpty()) {
                sent.add(new Sent(messageId, to, letter));
                return new AttemptResult.Accepted();
            }
            return new AttemptResult.Retryable("provider_timeout");
        };
        build();

        dispatcher.runOnce();

        assertEquals(2, issued.size());
        for (ReleasedKeys released : issued) {
            released.values().forEach(value -> {
                for (byte b : value.revealBytes()) {
                    assertEquals(0, b);
                }
            });
        }
    }

    @Tag("TC-603")
    @Test
    void nothingDueMeansNoCalls() {
        UUID id = add(1, ADDRESS, 2);
        clock.set(T0.minusSeconds(1));

        assertEquals(0, dispatcher.runOnce());

        assertEquals(0, keyRequests.size());
        assertEquals(0, sent.size());
        assertEquals("queued", deliveries.row(id).status);
    }

    @Tag("TC-603")
    @Test
    void thePassTakesTwentyDeliveriesOnTheLeaseOfTwoMinutes() {
        for (int i = 1; i <= 25; i++) {
            add(i, ADDRESS, 2);
        }

        assertEquals(20, dispatcher.runOnce());
        assertEquals(5, dispatcher.runOnce());
        assertEquals(0, dispatcher.runOnce());

        assertEquals(List.of(20, 20, 20), deliveries.limits());
        assertEquals(List.of(Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(2)), deliveries.leases());
        assertEquals(25, sent.size());
    }

    @Tag("TC-604")
    @Test
    void aTemporaryProviderErrorSpendsAnAttemptAndSchedulesTheNextOne() {
        UUID id = add(1, ADDRESS, 2);
        email = (messageId, to, letter) -> new AttemptResult.Retryable("provider_unavailable");
        build();

        dispatcher.runOnce();

        Row row = deliveries.row(id);
        assertEquals("queued", row.status);
        assertEquals(1, row.attemptNo);
        assertEquals("provider_unavailable", row.lastErrorCode);
        assertTrue(row.nextAttemptAt.isAfter(T0.plusSeconds(7)), "следующая попытка по расписанию, а не по концу аренды");
        assertTrue(row.nextAttemptAt.isBefore(T0.plusSeconds(13)), row.nextAttemptAt.toString());
        assertEquals(0, transaction.published().size());
        // срок не наступил: проход ничего не берёт, когда подходит срок, письмо уходит снова с тем же идентификатором и теми же ключами
        assertEquals(0, dispatcher.runOnce());
        clock.set(row.nextAttemptAt);
        email = (messageId, to, letter) -> {
            sent.add(new Sent(messageId, to, letter));
            return new AttemptResult.Accepted();
        };
        build();
        assertEquals(1, dispatcher.runOnce());
        assertEquals("sent", deliveries.row(id).status);
        assertEquals(2, deliveries.row(id).attemptNo);
        assertEquals(List.of("retryable_error", "accepted"), deliveries.attempts().stream().map(FakeDeliveries.Attempt::outcome).toList());
    }

    @Tag("TC-604")
    @Test
    void aPermanentProviderRefusalFailsTheDeliveryAtOnce() {
        UUID id = add(1, ADDRESS, 2);
        email = (messageId, to, letter) -> new AttemptResult.Permanent("address_rejected");
        build();

        dispatcher.runOnce();

        assertEquals("failed", deliveries.row(id).status);
        assertEquals("address_rejected", deliveries.row(id).failReason);
        assertEquals(1, transaction.published("delivery.failed").size());
        clock.advance(Duration.ofHours(1));
        assertEquals(0, dispatcher.runOnce(), "ошибочная выдача больше не берётся");
    }

    @Tag("TC-605")
    @Test
    void whenTheKeysAreNotReceivedTheLetterIsNotSentAndTheAttemptIsSpent() {
        UUID id = add(1, ADDRESS, 2);
        keys = orderId -> {
            throw new KeyRequestException("inventory_unavailable", "Сервис остатков недоступен", null);
        };
        build();

        dispatcher.runOnce();

        assertEquals(0, sent.size());
        Row row = deliveries.row(id);
        assertEquals("queued", row.status);
        assertEquals(1, row.attemptNo);
        assertEquals("inventory_unavailable", row.lastErrorCode);
        assertEquals("retryable_error", deliveries.attempts().get(0).outcome());
    }

    @Tag("TC-605")
    @Test
    void theNumberOfKeysDifferentFromTheOrderIsADataErrorAndTheLetterIsNotSent() {
        UUID id = add(1, ADDRESS, 3);

        dispatcher.runOnce();

        assertEquals(0, sent.size(), "неполное письмо не уходит (INV-19)");
        Row row = deliveries.row(id);
        assertEquals("queued", row.status);
        assertEquals("key_count_mismatch", row.lastErrorCode);
        assertEquals(1.0, meters.counter("delivery_anomalies_total", "reason", "key_count_mismatch").count());
        for (byte b : issued.get(0).values().get(0).revealBytes()) {
            assertEquals(0, b, "значения стёрты и при несоответствии");
        }
    }

    @Tag("TC-605")
    @Test
    void anAnonymizedAddressIsNeverSentAndNoKeysAreRequestedForIt() {
        UUID id = add(1, DeliveryAddress.ANONYMIZED, 2);

        dispatcher.runOnce();

        assertEquals(0, keyRequests.size());
        assertEquals(0, sent.size());
        Row row = deliveries.row(id);
        assertEquals("failed", row.status);
        assertEquals("address_rejected", row.failReason);
        assertEquals("address_anonymized", row.lastErrorCode);
        assertEquals("permanent_error", deliveries.attempts().get(0).outcome());
    }

    @Tag("TC-606")
    @Test
    void aFailureOfTheOutcomeWriteLeavesTheDeliveryOnItsLeaseAndDoesNotStopTheOthers() {
        UUID first = add(1, ADDRESS, 2);
        UUID second = add(2, ADDRESS, 2);
        deliveries.failOnMark = new IllegalStateException("база недоступна");

        assertEquals(2, dispatcher.runOnce(), "сбой записи не прерывает проход");
        deliveries.failOnMark = null;

        assertEquals(2, sent.size(), "письма ушли, исход не записан: после аренды уйдут ещё раз (хотя бы один раз)");
        assertEquals("queued", deliveries.row(first).status);
        assertEquals(0, deliveries.row(first).attemptNo);
        assertEquals(T0.plus(Duration.ofMinutes(2)), deliveries.row(first).nextAttemptAt, "срок сдвинут арендой");
        assertEquals(0, dispatcher.runOnce(), "пока аренда не истекла, выдачу никто не берёт");
        clock.advance(Duration.ofMinutes(2));
        assertEquals(2, dispatcher.runOnce(), "аренда истекла: выдачи снова наступили");
        assertEquals("sent", deliveries.row(first).status);
        assertEquals("sent", deliveries.row(second).status);
        assertEquals(4, sent.size());
        List<Sent> ofFirst = sent.stream().filter(m -> m.messageId().equals(first)).toList();
        assertEquals(2, ofFirst.size(), "повторное письмо несёт тот же идентификатор сообщения");
        assertEquals(ofFirst.get(0).letter().text(), ofFirst.get(1).letter().text(), "ключи при повторе те же");
    }
}
