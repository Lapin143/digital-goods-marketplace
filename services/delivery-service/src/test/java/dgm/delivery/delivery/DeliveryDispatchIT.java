package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.domain.ReleasedKeys;
import dgm.delivery.delivery.letter.Letter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Диспетчер выдачи на стенде (ADR-011, SM-06, INV-18 – INV-20): оплаченный заказ приходит событием {@code order.paid}, диспетчер берёт ключи
 * у сервиса остатков, отправляет одно письмо в почтовую заглушку и пишет исход попытки с событием в Outbox той же транзакцией. Проверяются
 * успех, повторы по расписанию, исчерпание попыток, отказ по адресу, сбои сервиса остатков, потерянный исход (аренда и дубль письма) и то, что
 * значений ключей нет ни в одной таблице сервиса.
 */
class DeliveryDispatchIT extends DeliveryStandIT {

    // ------------------------------------------------------------------------------------------------ TC-603: успешная выдача

    @Tag("TC-603")
    @Test
    void aPaidOrderGetsOneLetterWithAllItsKeysThePrimaryDeliveryIsSentAndTheAcceptedEventIsInTheOutbox() throws Exception {
        Placed p = place(3);
        List<String> keys = INVENTORY.issue(p.orderId(), 3);

        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))), "выдача не стала «отправлено», сейчас " + status(p));

        Map<String, Object> row = deliveryOf(p);
        assertEquals(1, ((Number) row.get("attempt_no")).intValue());
        assertEquals(false, row.get("scheduled"), "у отправленной выдачи следующей попытки нет");
        assertNull(row.get("last_error_code"));
        assertNull(row.get("fail_reason"));
        List<Map<String, Object>> attempts = attempts(p);
        assertEquals(1, attempts.size());
        assertEquals("accepted", attempts.get(0).get("outcome"));
        assertNull(attempts.get(0).get("error_code"));

        List<Map<String, Object>> mails = mails(p);
        assertEquals(1, mails.size(), "письмо одно");
        Map<String, Object> mail = mails.get(0);
        assertEquals(row.get("id").toString(), mail.get("messageId"), "идентификатор сообщения у провайдера это идентификатор выдачи");
        assertEquals(List.of(p.email()), mail.get("to"));
        assertTrue(((String) mail.get("subject")).contains(p.title()), "тема называет товар");
        String text = (String) mail.get("text");
        for (String key : keys) {
            assertTrue(text.contains(key), "в письме нет ключа");
        }
        assertTrue(text.contains("3 шт."), "в письме названо число ключей");
        assertEquals(1, INVENTORY.calls(p.orderId()).size(), "ключи запрошены один раз");

        List<Map<String, Object>> accepted = events(p, "delivery.accepted");
        assertEquals(1, accepted.size(), "delivery.accepted в Outbox той же транзакцией, что и исход попытки");
        assertEquals("delivery.events", accepted.get(0).get("topic"));
        assertEquals("Delivery", accepted.get(0).get("aggregate_type"));
        Map<String, Object> data = data(accepted.get(0));
        assertEquals(row.get("id").toString(), data.get("deliveryId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals("primary", data.get("deliveryType"));
        assertEquals(1, ((Number) data.get("attemptNo")).intValue());
        assertNotNull(data.get("acceptedAt"));
        assertEquals(0, events(p, "delivery.failed").size());
    }

    @Tag("TC-605")
    @Test
    void theValuesOfTheKeysAreInNoTableOfTheServiceAndNotInTheEventsOfTheOutbox() throws Exception {
        Placed p = place(2);
        List<String> keys = INVENTORY.issue(p.orderId(), 2);

        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))));

        assertSent(p, keys);
        for (String key : keys) {
            assertEquals(0, occurrences(key), "значение ключа найдено в базе сервиса выдачи (INV-20)");
        }
    }

    @Tag("TC-603")
    @Test
    void aDeliveryIsSentOnceAndTheNextPassesLeaveItAlone() throws Exception {
        Placed p = place(1);
        INVENTORY.issue(p.orderId(), 1);
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))));

        for (int i = 0; i < 3; i++) {
            dispatcher.runOnce();
            pause(300);
        }

        assertEquals(1, mails(p).size());
        assertEquals(1, attemptNo(p));
        assertEquals(1, INVENTORY.calls(p.orderId()).size());
        assertEquals(1, events(p, "delivery.accepted").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-604: повторы и отказы

    @Tag("TC-604")
    @Test
    void twoTemporaryRefusalsOfTheProviderAreFollowedByTheThirdAttemptWhichSendsTheLetter() throws Exception {
        Placed p = place(2);
        List<String> keys = INVENTORY.issue(p.orderId(), 2);
        emailMode(Map.of("behavior", "temp_error", "failFirst", 2));

        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))), "выдача не стала «отправлено», сейчас " + status(p));

        assertEquals(3, attemptNo(p));
        List<Map<String, Object>> attempts = attempts(p);
        assertEquals(3, attempts.size());
        assertEquals("retryable_error", attempts.get(0).get("outcome"));
        assertEquals("provider_unavailable", attempts.get(0).get("error_code"));
        assertEquals("retryable_error", attempts.get(1).get("outcome"));
        assertEquals("accepted", attempts.get(2).get("outcome"));
        assertSent(p, keys);
        assertEquals(3, INVENTORY.calls(p.orderId()).size(), "ключи запрашиваются заново при каждой попытке и нигде не хранятся");
        List<Map<String, Object>> accepted = events(p, "delivery.accepted");
        assertEquals(1, accepted.size(), "событие о принятом письме одно, на неудачные попытки событий нет");
        assertEquals(3, ((Number) data(accepted.get(0)).get("attemptNo")).intValue());
        assertEquals(0, events(p, "delivery.failed").size());
    }

    @Tag("TC-604")
    @Test
    void sixTemporaryRefusalsExhaustTheAttemptsTheDeliveryFailsAndTheFailedEventIsPublishedOnce() throws Exception {
        Placed p = place(1);
        List<String> keys = INVENTORY.issue(p.orderId(), 1);
        emailMode(Map.of("behavior", "temp_error"));

        assertTrue(drive(LIMIT, () -> "failed".equals(status(p))), "выдача не стала «ошибка», сейчас " + status(p));

        Map<String, Object> row = deliveryOf(p);
        assertEquals(6, ((Number) row.get("attempt_no")).intValue());
        assertEquals("attempts_exhausted", row.get("fail_reason"));
        assertEquals("provider_unavailable", row.get("last_error_code"));
        assertEquals(false, row.get("scheduled"));
        List<Map<String, Object>> attempts = attempts(p);
        assertEquals(6, attempts.size());
        for (Map<String, Object> attempt : attempts) {
            assertEquals("retryable_error", attempt.get("outcome"));
        }
        assertEquals(0, mails(p).size(), "письма не ушло");
        List<Map<String, Object>> failed = events(p, "delivery.failed");
        assertEquals(1, failed.size());
        assertEquals("delivery.events", failed.get(0).get("topic"));
        Map<String, Object> data = data(failed.get(0));
        assertEquals(row.get("id").toString(), data.get("deliveryId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals("primary", data.get("deliveryType"));
        assertEquals("attempts_exhausted", data.get("reason"));
        assertEquals(6, ((Number) data.get("attempts")).intValue());
        assertEquals(0, events(p, "delivery.accepted").size());
        for (String key : keys) {
            assertEquals(0, occurrences(key));
        }

        for (int i = 0; i < 3; i++) {
            dispatcher.runOnce();
            pause(300);
        }
        assertEquals(6, attemptNo(p), "после ошибки попыток больше нет");
        assertEquals(1, events(p, "delivery.failed").size());
    }

    @Tag("TC-604")
    @Test
    void aRefusalByTheAddressFailsTheDeliveryAtOnceWithoutRetries() throws Exception {
        Placed p = place(1);
        INVENTORY.issue(p.orderId(), 1);
        emailMode(Map.of("behavior", "perm_error"));

        assertTrue(drive(LIMIT, () -> "failed".equals(status(p))));

        Map<String, Object> row = deliveryOf(p);
        assertEquals(1, ((Number) row.get("attempt_no")).intValue());
        assertEquals("address_rejected", row.get("fail_reason"));
        assertEquals("address_rejected", row.get("last_error_code"));
        List<Map<String, Object>> attempts = attempts(p);
        assertEquals(1, attempts.size());
        assertEquals("permanent_error", attempts.get(0).get("outcome"));
        assertEquals("address_rejected", attempts.get(0).get("error_code"));
        List<Map<String, Object>> failed = events(p, "delivery.failed");
        assertEquals(1, failed.size());
        Map<String, Object> data = data(failed.get(0));
        assertEquals("address_rejected", data.get("reason"));
        assertEquals(1, ((Number) data.get("attempts")).intValue());
        pause(1500);
        dispatcher.runOnce();
        assertEquals(1, attemptNo(p), "повторов нет");
        assertEquals(0, events(p, "delivery.accepted").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-605: ключи недоступны

    @Tag("TC-605")
    @Test
    void whileTheInventoryServiceIsDownTheAttemptsFailWithoutALetterAndTheNextOneSendsItWhenTheServiceIsBack() throws Exception {
        Placed p = place(2);
        List<String> keys = INVENTORY.issue(p.orderId(), 2);
        INVENTORY.modeIs(InventoryStub.Mode.DOWN);

        assertTrue(drive(LIMIT, () -> attemptNo(p) >= 2), "не набралось двух попыток");
        assertEquals("queued", status(p), "две попытки из шести, выдача ещё в очереди");
        assertEquals(0, mails(p).size(), "без ключей письма нет");
        INVENTORY.modeIs(InventoryStub.Mode.OK);
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))), "выдача не стала «отправлено», сейчас " + status(p));

        List<Map<String, Object>> attempts = attempts(p);
        assertEquals("retryable_error", attempts.get(0).get("outcome"));
        assertEquals("inventory_unavailable", attempts.get(0).get("error_code"));
        assertEquals("inventory_unavailable", attempts.get(1).get("error_code"));
        assertEquals("accepted", attempts.get(attempts.size() - 1).get("outcome"));
        assertSent(p, keys);
    }

    @Tag("TC-605")
    @Test
    void keysNotYetIssuedAreATemporaryErrorOfTheAttempt() throws Exception {
        Placed p = place(1);
        List<String> keys = INVENTORY.issue(p.orderId(), 1);
        INVENTORY.modeIs(InventoryStub.Mode.NOT_ISSUED);

        assertTrue(drive(LIMIT, () -> attemptNo(p) >= 1));
        INVENTORY.modeIs(InventoryStub.Mode.OK);
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))));

        assertEquals("keys_not_issued", attempts(p).get(0).get("error_code"));
        assertSent(p, keys);
    }

    @Tag("TC-605")
    @Test
    void anOrderUnknownToTheInventoryServiceIsATemporaryErrorAndNoLetterGoesOut() throws Exception {
        Placed p = place(1);

        assertTrue(drive(LIMIT, () -> attemptNo(p) >= 1));

        assertEquals("keys_not_found", attempts(p).get(0).get("error_code"));
        assertEquals(0, mails(p).size());
    }

    @Tag("TC-605")
    @Test
    void aWrongNumberOfKeysNeverGoesOutAsALetterAndTheNextAttemptSendsTheRightOnes() throws Exception {
        Placed p = place(3);
        INVENTORY.issue(p.orderId(), 2);

        assertTrue(drive(LIMIT, () -> attemptNo(p) >= 1));

        assertEquals("key_count_mismatch", attempts(p).get(0).get("error_code"));
        assertEquals(0, mails(p).size(), "письма с чужим числом ключей нет (INV-19)");
        List<String> right = INVENTORY.issue(p.orderId(), 3);
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))));
        assertSent(p, right);
        assertEquals("accepted", attempts(p).get(attempts(p).size() - 1).get("outcome"));
    }

    @Tag("TC-605")
    @Test
    void anAnonymizedAddressNeverGetsALetterAndTheKeysAreNotEvenRequested() throws Exception {
        Placed p = place(1);
        INVENTORY.issue(p.orderId(), 1);
        db.update("update delivery.delivery set address = 'anonymized@anonymized.invalid' where order_id = ?", p.orderId());

        assertTrue(drive(LIMIT, () -> "failed".equals(status(p))));

        Map<String, Object> row = deliveryOf(p);
        assertEquals("address_rejected", row.get("fail_reason"));
        assertEquals("address_anonymized", row.get("last_error_code"));
        assertEquals(0, INVENTORY.calls(p.orderId()).size(), "ключи не запрашивались");
        assertEquals(0, stubs.emails("anonymized@anonymized.invalid", null, null).size());
        assertEquals(1, events(p, "delivery.failed").size());
    }

    // ------------------------------------------------------------------------------------------------ TC-606: потерянный исход

    @Tag("TC-606")
    @Test
    void aDeliveryTakenByAStalledPassStaysUntakenUntilItsLeaseEndsAndThenIsSentOnce() throws Exception {
        Placed p = place(1);
        List<String> keys = INVENTORY.issue(p.orderId(), 1);
        AtomicReference<Instant> claimedAt = new AtomicReference<>();

        // «упавший» проход: взял выдачу в аренду на две секунды и ничего не сделал
        assertTrue(await(LIMIT, () -> {
            Instant now = Instant.now();
            boolean mine = repository.claimDue(now, Duration.ofSeconds(2), 100).stream().anyMatch(d -> d.orderId().equals(p.orderId()));
            if (mine) {
                claimedAt.set(now);
            }
            return mine;
        }), "выдача не появилась в очереди");
        dispatcher.runOnce();

        if (Duration.between(claimedAt.get(), Instant.now()).compareTo(Duration.ofMillis(1_500)) < 0) {
            assertEquals(0, mails(p).size(), "пока аренда не истекла, выдачу никто не берёт");
            assertEquals(0, INVENTORY.calls(p.orderId()).size());
        }
        assertEquals(0, attemptNo(p), "упавший проход попытку не записал");
        assertEquals("queued", status(p));
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))), "после аренды выдача не отправлена");
        assertTrue(Duration.between(claimedAt.get(), Instant.now()).toMillis() >= 2_000, "выдача ушла раньше, чем истекла аренда");
        assertSent(p, keys);
        assertEquals(1, attemptNo(p));
        assertEquals(1, events(p, "delivery.accepted").size());
    }

    @Tag("TC-606")
    @Test
    void aLetterSentBeforeALostOutcomeIsSentAgainWithTheSameMessageIdAndTheSameKeysAndTheProviderDeduplicatesIt() throws Exception {
        Placed p = place(2);
        List<String> keys = INVENTORY.issue(p.orderId(), 2);
        UUID deliveryId = idOf(p);

        // «упавший» процесс: провайдер письмо принял, исход записать не успели
        Letter first;
        try (ReleasedKeys released = keyClient.keysOf(p.orderId())) {
            first = letterBuilder.build(p.title(), p.quantity(), released);
        }
        emailClient.send(deliveryId, p.email(), first);
        assertEquals(1, mails(p).size());
        assertEquals("queued", status(p));

        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))));

        List<Map<String, Object>> mails = mails(p);
        assertEquals(1, mails.size(), "провайдер по тому же идентификатору второго письма не создаёт");
        assertEquals(2, ((Number) mails.get(0).get("receivedCount")).intValue(), "но повторный вызов принял");
        assertEquals(deliveryId.toString(), mails.get(0).get("messageId"));
        assertEquals(1, attemptNo(p));
        assertEquals(1, events(p, "delivery.accepted").size());
        assertSent(p, keys);
    }

    @Tag("TC-606")
    @Test
    void withoutDeduplicationAtTheProviderTheRareDuplicateHasTheSameTextAndNoNewKeys() throws Exception {
        Placed p = place(2);
        List<String> keys = INVENTORY.issue(p.orderId(), 2);
        emailMode(Map.of("dedupe", false));
        UUID deliveryId = idOf(p);
        Letter first;
        try (ReleasedKeys released = keyClient.keysOf(p.orderId())) {
            first = letterBuilder.build(p.title(), p.quantity(), released);
        }
        emailClient.send(deliveryId, p.email(), first);

        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))));

        List<Map<String, Object>> mails = mails(p);
        assertEquals(2, mails.size(), "дубль письма допустим: доставка гарантирована хотя бы один раз (ADR-011)");
        assertEquals(mails.get(0).get("messageId"), mails.get(1).get("messageId"));
        assertEquals(mails.get(0).get("text"), mails.get(1).get("text"), "ключи в дубле те же, новых не выдано");
        for (String key : keys) {
            assertTrue(((String) mails.get(1).get("text")).contains(key));
        }
        assertFalse(events(p, "delivery.accepted").isEmpty());
        assertEquals(1, events(p, "delivery.accepted").size(), "событие о принятом письме одно");
    }
}
