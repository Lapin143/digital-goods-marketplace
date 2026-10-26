package dgm.platform.notification.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.time.ManualClock;
import dgm.platform.identity.api.SellerRecipients;
import dgm.platform.notification.letter.Letter;
import dgm.platform.notification.letter.Letters;
import dgm.platform.notification.service.NotificationService;
import dgm.platform.notification.testing.InMemoryNotifications;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Письма продавцу о решении модератора по товару (FT-11.4, шаг 16 Ф4): получатель находится по профилю продавца, а не по идентификатору
 * из события; причина идёт параметром только там, где она есть; блокировка вместе с продавцом письма по товару не даёт.
 */
@Tag("TC-719")
class ProductDecisionHandlerTest {

    private static final UUID OWNER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000c1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-4000-8000-0000000000e1");
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final String TITLE = "Ключ Steam, подарочная карта 1500 ₽";

    private final Letters letters = new Letters(ZoneId.of("Europe/Moscow"), "МСК");
    private InMemoryNotifications queue;
    private ProductDecisionHandler handler;
    private boolean sellerKnown;

    @BeforeEach
    void setUp() {
        queue = new InMemoryNotifications();
        sellerKnown = true;
        SellerRecipients sellers = id -> sellerKnown && SELLER.equals(id) ? Optional.of(OWNER) : Optional.empty();
        handler = new ProductDecisionHandler(new NotificationService(queue, letters, new ManualClock(NOW)), sellers);
    }

    private static EventEnvelope event(String type, Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), "catalog-service", type, NOW, PRODUCT.toString(), 1, "Product",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba90222-01", UUID.randomUUID().toString(), data);
    }

    private static Map<String, Object> data(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("productId", PRODUCT.toString());
        map.put("sellerId", SELLER.toString());
        map.put("title", TITLE);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private InMemoryNotifications.Row onlyRow() {
        assertEquals(1, queue.all().size());
        return queue.all().get(0);
    }

    @Test
    void handlerDeclaresTheThreeProductDecisionsOfCatalogTopicAndTheNotificationModule() {
        assertEquals("catalog.events", handler.topic());
        assertEquals(Set.of("product.published", "product.rejected", "product.blocked"), handler.types());
        assertEquals("notification", handler.module());
        assertEquals(1, handler.maxSchemaVersion());
        assertEquals("notification-handler", handler.name());
    }

    @Test
    void publishedProductGivesTheLetterToTheOwnerOfTheProfileWithTheTitleOnly() {
        EventEnvelope event = event("product.published", data());

        assertEquals(HandleResult.done(), handler.handle(event));

        InMemoryNotifications.Row row = onlyRow();
        assertEquals(OWNER, row.source.userId(), "письмо идёт владельцу профиля, а не по идентификатору профиля");
        assertEquals("product-published", row.source.template());
        assertEquals(Map.of("title", TITLE), row.source.params());
        assertEquals(event.id(), row.source.sourceEventId());
    }

    @Test
    void rejectedProductCarriesTheTitleAndTheModeratorReasonVerbatim() {
        handler.handle(event("product.rejected", data("reason", "Описание не соответствует товару")));

        InMemoryNotifications.Row row = onlyRow();
        assertEquals("product-rejected", row.source.template());
        assertEquals(Map.of("title", TITLE, "reason", "Описание не соответствует товару"), row.source.params());
    }

    @Test
    void blockedByTheModeratorCarriesTheTitleAndTheReason() {
        handler.handle(event("product.blocked", data("reason", "Жалобы на недействительные ключи", "cause", "moderator_decision")));

        InMemoryNotifications.Row row = onlyRow();
        assertEquals("product-blocked", row.source.template());
        assertEquals(Map.of("title", TITLE, "reason", "Жалобы на недействительные ключи"), row.source.params());
    }

    @Test
    void blockingTheWholeSellerGivesNoLetterPerProduct() {
        HandleResult result = handler.handle(event("product.blocked", data("reason", "Блокировка продавца", "cause", "seller_blocked")));

        assertFalse(result.applied());
        assertEquals("seller-blocked", result.reason());
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void profileThatBelongsToNoAccountLeavesNoLetterAndIsReportedAsSkipped() {
        sellerKnown = false;

        HandleResult result = handler.handle(event("product.published", data()));

        assertFalse(result.applied());
        assertEquals("seller-unknown", result.reason());
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void sameEventHandledTwiceGivesOneLetter() {
        EventEnvelope event = event("product.rejected", data("reason", "Причина"));

        handler.handle(event);
        HandleResult second = handler.handle(event);

        assertFalse(second.applied());
        assertEquals("duplicate-notification", second.reason());
        assertEquals(1, queue.all().size());
    }

    @Test
    void twoDecisionsOnOneProductGiveTwoLetters() {
        handler.handle(event("product.rejected", data("reason", "Причина")));
        handler.handle(event("product.published", data()));

        assertEquals(2, queue.all().size());
    }

    @Test
    void publishedLetterHasNoReasonLinesAndNoUnfilledFields() {
        handler.handle(event("product.published", data()));

        Letter letter = letters.render("product-published", "Анна Смирнова", onlyRow().source.params());

        assertEquals("Товар опубликован", letter.subject());
        assertTrue(letter.text().startsWith("Здравствуйте, Анна Смирнова!"), letter.text());
        assertTrue(letter.text().contains("«" + TITLE + "»"), letter.text());
        assertFalse(letter.text().contains("Причина"), letter.text());
        assertFalse(letter.text().contains("{"), letter.text());
        assertFalse(letter.text().contains("null"), letter.text());
        assertFalse(letter.text().contains("\n\n\n"), "пустых строк подряд нет: " + letter.text());
    }

    @Test
    void rejectedAndBlockedLettersShowTheReasonAndNothingElseFromTheEvent() {
        handler.handle(event("product.rejected", data("reason", "Описание не соответствует товару")));
        handler.handle(event("product.blocked", data("reason", "Жалобы на недействительные ключи", "cause", "moderator_decision")));

        Letter rejected = letters.render("product-rejected", "Анна", queue.all().get(0).source.params());
        Letter blocked = letters.render("product-blocked", "Анна", queue.all().get(1).source.params());

        assertEquals("Товар отклонён модератором", rejected.subject());
        assertTrue(rejected.text().contains("Описание не соответствует товару"), rejected.text());
        assertEquals("Товар снят с витрины", blocked.subject());
        assertTrue(blocked.text().contains("Жалобы на недействительные ключи"), blocked.text());
        for (Letter letter : new Letter[] {rejected, blocked}) {
            assertFalse(letter.text().contains(PRODUCT.toString()), "идентификаторы в письме не нужны");
            assertFalse(letter.text().contains(SELLER.toString()), "идентификаторы в письме не нужны");
        }
    }

    @Test
    void moderatorTextWithBracesIsNotInterpretedAsAField() {
        handler.handle(event("product.rejected", data("reason", "Уберите {name} и {siteUrl} из описания")));

        Letter letter = letters.render("product-rejected", "Анна", onlyRow().source.params());

        assertTrue(letter.text().contains("Уберите {name} и {siteUrl} из описания"), letter.text());
    }

    @Test
    void eventsThatBreakTheSchemaGoStraightToDlq() {
        assertThrows(FatalEventException.class, () -> handler.handle(event("product.published", data("sellerId", "42"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("product.published", data("title", ""))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("product.published", data("title", "я".repeat(201)))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("product.rejected", data())), "у отказа нет причины");
        assertThrows(FatalEventException.class, () -> handler.handle(event("product.blocked", data("reason", "Причина"))), "у блокировки нет источника");
        assertThrows(FatalEventException.class, () -> handler.handle(event("product.blocked", data("cause", "moderator_decision"))));
        assertThrows(FatalEventException.class, () -> handler.handle(event("product.archived", data())));
        assertTrue(queue.all().isEmpty());
    }

    @Test
    void failureMessagesNameTheFieldButNotTheModeratorText() {
        FatalEventException e = assertThrows(FatalEventException.class,
                () -> handler.handle(event("product.rejected", data("reason", "Секретный текст модератора " + "я".repeat(1000)))));

        assertFalse(e.getMessage().contains("Секретный"), e.getMessage());
        assertTrue(e.getMessage().contains("reason"), e.getMessage());
    }
}
