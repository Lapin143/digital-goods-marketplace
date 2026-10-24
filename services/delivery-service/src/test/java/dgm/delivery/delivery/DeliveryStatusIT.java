package dgm.delivery.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.crypto.WebhookSignature;
import dgm.kit.json.Json;
import dgm.kit.time.Timestamps;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Статусы писем и контроль 30 минут на стенде (ADR-011, SM-06 T5 и T6, NFT-2.4, E11): подписанный вебхук {@code receiveKeyEmailStatus} и опрос
 * провайдера применяют один и тот же код; настоящие PostgreSQL, Kafka (Outbox) и почтовая заглушка. Контроль считает срок от оплаты, поэтому
 * заказ с оплатой «40 минут назад» приходит событием с этим временем, а не ждёт 30 минут. Гонки в {@link DeliveryStatusConcurrencyIT}.
 */
class DeliveryStatusIT extends DeliveryStatusStandIT {

    private static final Duration WINDOW = Duration.ofMinutes(30);

    // ------------------------------------------------------------------------------------------------ TC-607: вебхук

    @Tag("TC-607")
    @Test
    void aDeliveredNotificationClosesTheDeliveryWithOneEventAndClosesTheWatch() throws Exception {
        Placed p = place(2);
        UUID id = sent(p);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        assertEquals("open", watchStatus(p));

        assertOk(send(notice(id, "delivered", at, null)));

        assertEquals("delivered", status(p));
        assertEquals(at, deliveredAt(p), "время доставки по данным провайдера");
        assertEquals("closed", watchStatus(p));
        assertEquals(List.of("delivered"), providerStatuses(id));
        List<Map<String, Object>> delivered = events(p, "delivery.delivered");
        assertEquals(1, delivered.size());
        assertEquals("delivery.events", delivered.get(0).get("topic"));
        assertEquals("Delivery", delivered.get(0).get("aggregate_type"));
        Map<String, Object> data = data(delivered.get(0));
        assertEquals(id.toString(), data.get("deliveryId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals("primary", data.get("deliveryType"));
        assertEquals(at, Instant.parse((String) data.get("deliveredAt")));
        assertEquals(1, events(p, "delivery.accepted").size(), "событие о принятом письме остаётся одно");
        assertEquals(0, events(p, "delivery.failed").size());
        assertEquals(0, events(p, "delivery.overdue").size());
    }

    @Tag("TC-607")
    @Test
    void aRepeatedNotificationIsAnsweredWithOkAndChangesNothing() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);
        Map<String, Object> body = notice(id, "delivered");

        assertOk(send(body));
        Instant first = deliveredAt(p);
        assertOk(send(body));
        assertOk(send(notice(id, "delivered", Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS), null)));

        assertEquals("delivered", status(p));
        assertEquals(first, deliveredAt(p), "время доставки не перезаписывается");
        assertEquals(List.of("delivered"), providerStatuses(id));
        assertEquals(1, events(p, "delivery.delivered").size());
    }

    @Tag("TC-607")
    @Test
    void aFailedNotificationFailsTheDeliveryWithAddressRejectedAndOneFailedEvent() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);

        assertOk(send(notice(id, "failed", Instant.now().truncatedTo(ChronoUnit.MILLIS), "address_rejected")));

        Map<String, Object> row = deliveryOf(p);
        assertEquals("failed", row.get("status"));
        assertEquals("address_rejected", row.get("fail_reason"));
        assertEquals("provider_address_rejected", row.get("last_error_code"));
        assertEquals(List.of("failed"), providerStatuses(id));
        List<Map<String, Object>> failed = events(p, "delivery.failed");
        assertEquals(1, failed.size());
        Map<String, Object> data = data(failed.get(0));
        assertEquals(id.toString(), data.get("deliveryId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals("primary", data.get("deliveryType"));
        assertEquals("address_rejected", data.get("reason"));
        assertEquals(1, ((Number) data.get("attempts")).intValue());
        assertEquals(0, events(p, "delivery.delivered").size());
    }

    @Tag("TC-607")
    @Test
    void aFailureOfAnotherKindIsAlsoAddressRejectedBecauseTheProviderDidNotDeliverTheLetter() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);

        assertOk(send(notice(id, "failed", Instant.now().truncatedTo(ChronoUnit.MILLIS), "other")));

        Map<String, Object> row = deliveryOf(p);
        assertEquals("failed", row.get("status"));
        assertEquals("address_rejected", row.get("fail_reason"), "Р-152: письмо не доставлено, причина «принято, но не доставлено» в модели одна");
        assertEquals("provider_other", row.get("last_error_code"));
        assertEquals("address_rejected", data(events(p, "delivery.failed").get(0)).get("reason"));
    }

    @Tag("TC-607")
    @Test
    void anAcceptedNotificationIsRecordedAndTheDeliveryStaysSentUntilTheFinalStatus() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);

        assertOk(send(notice(id, "accepted")));
        assertEquals("sent", status(p));
        assertEquals(List.of("accepted"), providerStatuses(id));
        assertEquals("open", watchStatus(p));

        assertOk(send(notice(id, "delivered")));
        assertEquals("delivered", status(p));
        assertEquals(List.of("accepted", "delivered"), providerStatuses(id));
    }

    @Tag("TC-607")
    @Test
    void aStatusAfterAFinalOneDoesNotRevokeIt() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);
        assertOk(send(notice(id, "delivered")));

        assertOk(send(notice(id, "failed", Instant.now().truncatedTo(ChronoUnit.MILLIS), "address_rejected")));

        assertEquals("delivered", status(p), "подтверждённая доставка не отзывается");
        assertEquals(List.of("delivered"), providerStatuses(id), "статус, который не вписался, не записывается");
        assertEquals(0, events(p, "delivery.failed").size());
        assertEquals(1, events(p, "delivery.delivered").size());
    }

    @Tag("TC-607")
    @Test
    void aStatusThatOvertakesTheOutcomeOfTheAttemptIsAnsweredWithOkAndLeftToThePoller() throws Exception {
        Placed p = place(1);
        UUID id = idOf(p);
        assertEquals("queued", status(p));

        assertOk(send(notice(id, "delivered")));

        assertEquals("queued", status(p), "выдача ещё в очереди: статус не применяется");
        assertEquals(List.of(), providerStatuses(id), "и не записывается, иначе опрос принял бы его за дубль");
        assertEquals(0, outcomeEvents(p));

        INVENTORY.issue(p.orderId(), 1);
        assertTrue(drive(LIMIT, () -> "sent".equals(status(p))));
        providerSays(id, "delivered", null);
        assertTrue(poll(LIMIT, () -> "delivered".equals(status(p))), "опрос применил статус, не доставленный вебхуком");
        assertEquals(1, events(p, "delivery.delivered").size());
    }

    @Tag("TC-607")
    @Test
    void aNotificationOfAnUnknownMessageIsAnsweredWithOkAndWritesNothing() throws Exception {
        long statusesBefore = count("select count(*) from delivery.provider_status_event");
        long outboxBefore = count("select count(*) from public.outbox where event_type like 'delivery.%'");

        assertOk(send(notice(UUID.randomUUID(), "delivered")));
        assertOk(send(notice("not-a-uuid", "failed", Instant.now().truncatedTo(ChronoUnit.MILLIS), "other")));
        assertOk(send(notice(UUID.randomUUID().toString().toUpperCase(), "delivered")));

        assertEquals(statusesBefore, count("select count(*) from delivery.provider_status_event"));
        assertEquals(outboxBefore, count("select count(*) from public.outbox where event_type like 'delivery.%'"));
    }

    @Tag("TC-607")
    @Test
    void aStatusOfAnotherKindFromTheProviderIsAnsweredWithOkAndIgnored() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);

        assertOk(send(notice(id, "bounced")));

        assertEquals("sent", status(p));
        assertEquals(List.of(), providerStatuses(id));
    }

    // ------------------------------------------------------------------------------------------------ TC-607: подпись и тело

    @Tag("TC-607")
    @Test
    void aForgedOrStaleNotificationIsRefusedAndNothingIsWritten() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);
        String body = Json.write(notice(id, "delivered"));
        String now = Timestamps.format(Instant.now());

        Response wrongSecret = gatewayCaller.post(PATH, body, WebhookSignature.TIMESTAMP_HEADER, now, WebhookSignature.SIGNATURE_HEADER, "0".repeat(64));
        assertEquals(401, wrongSecret.status(), wrongSecret.body());
        assertEquals("signature-invalid", code(wrongSecret));

        Response tampered = gatewayCaller.post(PATH, body.replace("delivered", "deliverеd"), WebhookSignature.TIMESTAMP_HEADER, now,
                WebhookSignature.SIGNATURE_HEADER, sign(now, body));
        assertEquals(401, tampered.status());

        assertEquals(401, gatewayCaller.post(PATH, body).status(), "без заголовков подписи");

        String old = Timestamps.format(Instant.now().minus(Duration.ofMinutes(30)));
        Response stale = gatewayCaller.post(PATH, body, WebhookSignature.TIMESTAMP_HEADER, old, WebhookSignature.SIGNATURE_HEADER, sign(old, body));
        assertEquals(401, stale.status());
        assertEquals("webhook-expired", code(stale), "подпись верна, метка старше пяти минут: повторная отправка перехваченного тела");

        assertEquals("sent", status(p));
        assertEquals(List.of(), providerStatuses(id));
        assertEquals(0, events(p, "delivery.delivered").size());
    }

    @Tag("TC-607")
    @Test
    void aSignedBodyThatBreaksTheSchemaIs400AndAWrongContentTypeIs415() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);

        Map<String, Object> extra = notice(id, "delivered");
        extra.put("surprise", 1);
        Response extraField = send(extra);
        assertEquals(400, extraField.status(), extraField.body());
        assertEquals("bad-request", code(extraField));

        Map<String, Object> noStatus = notice(id, "delivered");
        noStatus.remove("status");
        assertEquals(400, send(noStatus).status());

        Map<String, Object> noTime = notice(id, "delivered");
        noTime.remove("occurredAt");
        assertEquals(400, send(noTime).status());

        Map<String, Object> badReason = notice(id, "failed", Instant.now(), "no_such_reason");
        assertEquals(400, send(badReason).status());

        assertEquals(400, send("{не JSON").status());

        String body = Json.write(notice(id, "delivered"));
        String now = Timestamps.format(Instant.now());
        Response plain = gatewayCaller.request("POST", PATH, "text/plain", body.getBytes(StandardCharsets.UTF_8), WebhookSignature.TIMESTAMP_HEADER, now,
                WebhookSignature.SIGNATURE_HEADER, sign(now, body));
        assertEquals(415, plain.status(), plain.body());
        assertEquals("unsupported-media-type", code(plain));

        assertEquals("sent", status(p));
        assertEquals(List.of(), providerStatuses(id));
    }

    @Tag("TC-607")
    @Test
    void aBodyLargerThanSixtyFourKilobytesIs413() throws Exception {
        Response response = send("{\"messageId\":\"" + "x".repeat(70_000) + "\"}");

        assertEquals(413, response.status(), response.body());
    }

    // ------------------------------------------------------------------------------------------------ TC-607: опрос

    @Tag("TC-607")
    @Test
    void aLostWebhookIsClosedByThePollerWithTheSameEventAndTheWatchIsClosed() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);
        providerSays(id, "delivered", null);

        assertTrue(poll(LIMIT, () -> "delivered".equals(status(p))), "опрос не закрыл выдачу, сейчас " + status(p));

        assertNotNull(deliveredAt(p));
        assertEquals("closed", watchStatus(p));
        assertEquals(List.of("delivered"), providerStatuses(id));
        assertEquals(1, events(p, "delivery.delivered").size());
        poller.runOnce();
        assertEquals(1, events(p, "delivery.delivered").size(), "закрытая выдача больше не опрашивается");
    }

    @Tag("TC-607")
    @Test
    void aFailureReportedByTheProviderIsClosedByThePoller() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);
        providerSays(id, "failed", "address_rejected");

        assertTrue(poll(LIMIT, () -> "failed".equals(status(p))), "опрос не закрыл выдачу, сейчас " + status(p));

        Map<String, Object> row = deliveryOf(p);
        assertEquals("address_rejected", row.get("fail_reason"));
        assertEquals("provider_address_rejected", row.get("last_error_code"));
        assertEquals(1, events(p, "delivery.failed").size());
        assertEquals(0, events(p, "delivery.delivered").size());
    }

    @Tag("TC-607")
    @Test
    void aMessageThatTheProviderStillHasAsAcceptedStaysSentAndIsAskedAgain() throws Exception {
        Placed p = place(1);
        UUID id = sent(p);

        for (int i = 0; i < 3; i++) {
            poller.runOnce();
        }

        assertEquals("sent", status(p));
        assertEquals(List.of(), providerStatuses(id), "статус accepted опрос не записывает");

        providerSays(id, "delivered", null);
        assertTrue(poll(LIMIT, () -> "delivered".equals(status(p))), "вопрос повторён и получил ответ");
    }

    // ------------------------------------------------------------------------------------------------ TC-608: контроль 30 минут

    @Tag("TC-608")
    @Test
    void anOrderNotDeliveredInThirtyMinutesIsHandedOverOnceWithTheOverdueEventAndTheDeliveryStaysSent() throws Exception {
        Instant paidAt = Instant.now().minus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.MILLIS);
        Placed p = placeAt(paidAt, 1);
        UUID id = sent(p);
        Instant deadline = db.queryForObject("select deadline_at from delivery.delivery_watch where order_id = ?", java.sql.Timestamp.class, p.orderId())
                .toInstant();
        assertEquals(paidAt.plus(WINDOW), deadline, "срок контроля это оплата плюс 30 минут");

        assertTrue(keepWatch(LIMIT, () -> !"open".equals(watchStatus(p))), "контроль не передан в поддержку");

        assertEquals("handed_over", watchStatus(p));
        assertEquals("sent", status(p), "контроль статус выдачи не меняет (SM-06)");
        List<Map<String, Object>> overdue = events(p, "delivery.overdue");
        assertEquals(1, overdue.size());
        assertEquals("delivery.events", overdue.get(0).get("topic"));
        assertEquals("Delivery", overdue.get(0).get("aggregate_type"));
        Map<String, Object> data = data(overdue.get(0));
        assertEquals(id.toString(), data.get("deliveryId"));
        assertEquals(p.orderId().toString(), data.get("orderId"));
        assertEquals(paidAt, Instant.parse((String) data.get("paidAt")));
        assertEquals(1800, ((Number) data.get("thresholdSeconds")).intValue());

        for (int i = 0; i < 3; i++) {
            watch.runOnce();
        }
        assertEquals(1, events(p, "delivery.overdue").size(), "событие одно, сколько бы проходов ни было");
    }

    @Tag("TC-608")
    @Test
    void anOrderStillInTheQueueWhenTheWindowEndsIsHandedOverAsWell() throws Exception {
        Placed p = placeAt(Instant.now().minus(Duration.ofMinutes(45)), 1);
        assertEquals("queued", status(p));

        assertTrue(keepWatch(LIMIT, () -> !"open".equals(watchStatus(p))), "контроль не передан в поддержку");

        assertEquals("handed_over", watchStatus(p));
        assertEquals("queued", status(p));
        assertEquals(1, events(p, "delivery.overdue").size());
        assertEquals(idOf(p).toString(), data(events(p, "delivery.overdue").get(0)).get("deliveryId"));
    }

    @Tag("TC-608")
    @Test
    void anOrderDeliveredInTimeIsNeverHandedOver() throws Exception {
        Placed p = placeAt(Instant.now().minus(Duration.ofMinutes(40)), 1);
        UUID id = sent(p);
        assertOk(send(notice(id, "delivered")));

        assertEquals("closed", watchStatus(p), "доставка закрывает контроль сразу");
        for (int i = 0; i < 3; i++) {
            watch.runOnce();
            pause(100);
        }

        assertEquals("closed", watchStatus(p));
        assertEquals(0, events(p, "delivery.overdue").size());
        assertEquals(1, events(p, "delivery.delivered").size());
    }

    @Tag("TC-608")
    @Test
    void aWatchWhoseWindowHasNotPassedIsLeftAlone() throws Exception {
        Placed p = place(1);
        sent(p);

        for (int i = 0; i < 3; i++) {
            watch.runOnce();
            pause(100);
        }

        assertEquals("open", watchStatus(p));
        assertEquals(0, events(p, "delivery.overdue").size());
    }

    @Tag("TC-608")
    @Test
    void aLateDeliveryAfterTheHandOverClosesTheWatchAndKeepsBothEvents() throws Exception {
        Placed p = placeAt(Instant.now().minus(Duration.ofMinutes(40)), 1);
        UUID id = sent(p);
        assertTrue(keepWatch(LIMIT, () -> "handed_over".equals(watchStatus(p))));

        assertOk(send(notice(id, "delivered")));

        assertEquals("delivered", status(p));
        assertEquals("closed", watchStatus(p), "запоздавшая доставка закрывает обращение");
        assertEquals(1, events(p, "delivery.overdue").size());
        assertEquals(1, events(p, "delivery.delivered").size());
        assertNull(deliveryOf(p).get("fail_reason"));
    }
}
