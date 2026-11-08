package dgm.platform.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.notification.domain.NewWebhookDelivery;
import dgm.platform.notification.domain.WebhookDelivery;
import dgm.platform.notification.domain.WebhookDeliveryFilter;
import dgm.platform.notification.domain.WebhookDeliveryPage;
import dgm.platform.notification.testing.InMemoryWebhookDeliveries;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Список доставок продавца (TC-972, OP-102): только свои, новые выше, страница не длиннее {@code limit}, курсор ставится по последней показанной
 * строке, отбор по статусам; тела уведомления в строке нет.
 */
@Tag("TC-972")
class WebhookDeliveryServiceTest {

    private static final UUID ANNA = UUID.fromString("0199e0a0-0000-7000-8000-000000000101");
    private static final UUID IVAN = UUID.fromString("0199e0a0-0000-7000-8000-000000000102");
    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    private InMemoryWebhookDeliveries queue;
    private WebhookDeliveryService service;

    @BeforeEach
    void setUp() {
        queue = new InMemoryWebhookDeliveries();
        service = new WebhookDeliveryService(queue);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("0199e0a0-0000-7000-8000-%012d", n));
    }

    /** Ставит уведомление: номер {@code n} даёт идентификатор, событие и номер заказа 1000 + n; время постановки T0 + n секунд. */
    private void enqueue(UUID seller, int n) {
        assertTrue(queue.insert(new NewWebhookDelivery(id(n), seller, id(5000 + n), "order.issued", 1000L + n, Map.of("type", "order.issued"), T0.plusSeconds(n))));
    }

    private static WebhookDeliveryFilter filter(UUID seller, int limit) {
        return new WebhookDeliveryFilter(seller, Optional.empty(), limit, Optional.empty());
    }

    private static List<UUID> ids(WebhookDeliveryPage page) {
        List<UUID> ids = new ArrayList<>();
        for (WebhookDelivery d : page.items()) {
            ids.add(d.id());
        }
        return ids;
    }

    @Test
    void onlyTheOwnNotificationsAreListedNewestFirst() {
        enqueue(ANNA, 1);
        enqueue(IVAN, 2);
        enqueue(ANNA, 3);

        assertEquals(List.of(id(3), id(1)), ids(service.list(filter(ANNA, 20))));
        assertEquals(List.of(id(2)), ids(service.list(filter(IVAN, 20))));
        assertEquals(List.of(), ids(service.list(filter(UUID.randomUUID(), 20))), "у продавца без уведомлений список пуст, чужие не подмешиваются");
    }

    @Test
    void aPageIsNoLongerThanTheLimitAndTheCursorPointsAtTheLastShownRow() {
        for (int n = 1; n <= 5; n++) {
            enqueue(ANNA, n);
        }

        WebhookDeliveryPage first = service.list(filter(ANNA, 2));

        assertEquals(List.of(id(5), id(4)), ids(first));
        assertEquals(2, first.limit());
        assertEquals(Optional.of(new WebhookDeliveryFilter.After(T0.plusSeconds(4), id(4))), first.next(), "курсор по последней показанной строке, лишняя прочитанная не показывается");
    }

    @Test
    void theCursorPassesEveryRowExactlyOnceAndTheLastPageHasNoCursor() {
        for (int n = 1; n <= 5; n++) {
            enqueue(ANNA, n);
        }
        List<UUID> seen = new ArrayList<>();
        Optional<WebhookDeliveryFilter.After> after = Optional.empty();
        int pages = 0;
        do {
            WebhookDeliveryPage page = service.list(new WebhookDeliveryFilter(ANNA, Optional.empty(), 2, after));
            seen.addAll(ids(page));
            after = page.next();
            pages++;
        } while (after.isPresent() && pages < 10);

        assertEquals(List.of(id(5), id(4), id(3), id(2), id(1)), seen);
        assertEquals(3, pages);
        assertTrue(after.isEmpty());
    }

    @Test
    void anExactlyFullLastPageDoesNotInventANextOne() {
        enqueue(ANNA, 1);
        enqueue(ANNA, 2);

        WebhookDeliveryPage page = service.list(filter(ANNA, 2));

        assertEquals(2, page.items().size());
        assertTrue(page.next().isEmpty(), "строк ровно на страницу: следующей нет");
    }

    @Test
    void theFilterByStatusKeepsTheOrderAndAStatusWithoutRowsGivesAnEmptyList() {
        for (int n = 1; n <= 4; n++) {
            enqueue(ANNA, n);
        }
        queue.markSent(id(1), 1, 200, T0.plusSeconds(10));
        queue.markSkipped(id(3), "no_endpoint");

        assertEquals(List.of(id(4), id(2)), ids(service.list(new WebhookDeliveryFilter(ANNA, Optional.of(List.of("queued")), 20, Optional.empty()))));
        assertEquals(List.of(id(3), id(1)), ids(service.list(new WebhookDeliveryFilter(ANNA, Optional.of(List.of("sent", "skipped")), 20, Optional.empty()))));
        assertTrue(service.list(new WebhookDeliveryFilter(ANNA, Optional.of(List.of("failed")), 20, Optional.empty())).items().isEmpty());
    }

    @Test
    void aRowCarriesTheFieldsOfTheListAndNothingMore() {
        enqueue(ANNA, 1);
        queue.reschedule(id(1), 1, T0.plusSeconds(60), 503, "seller_http_503", T0.plusSeconds(1));

        WebhookDelivery row = service.list(filter(ANNA, 20)).items().get(0);

        assertEquals(id(1), row.id());
        assertEquals(id(5001), row.eventId());
        assertEquals("order.issued", row.eventType());
        assertEquals(1001L, row.orderNumber());
        assertEquals("queued", row.status());
        assertEquals(1, row.attempts());
        assertEquals(503, row.lastResponseCode());
        assertEquals(T0.plusSeconds(1), row.lastAttemptAt());
        assertEquals(T0.plusSeconds(60), row.nextAttemptAt());
        assertEquals(T0.plusSeconds(1), row.createdAt());
        assertFalse(row.toString().contains("payload"));
    }
}
