package dgm.platform.notification.letter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.notification.api.InvalidNotificationException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Шаблоны писем среза 1: поля подставляются, лишнего нет (шаг 8 Ф4, решения Р-3, Р-46, INV-44). */
class LettersTest {

    private static final Pattern LEFTOVER = Pattern.compile("\\{[A-Za-z]+}");
    private final Letters letters = new Letters(ZoneId.of("Europe/Moscow"), "МСК");

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void thereAreExactlyTheFourTemplatesOfSliceOne() {
        assertEquals(Set.of("seller-approved", "seller-rejected", "seller-returned", "user-sms-login"), letters.codes());
    }

    @Test
    void approvedLetterNamesTheRecipientTheProfileTypeAndTheSecondFactor() {
        Letter letter = letters.render("seller-approved", "Иван Петров", params("sellerType", "self_employed"));

        assertEquals("Ваша заявка продавца одобрена", letter.subject());
        assertTrue(letter.text().startsWith("Здравствуйте, Иван Петров!"), letter.text());
        assertTrue(letter.text().contains("Тип профиля: самозанятый."), letter.text());
        assertTrue(letter.text().contains("второй фактор"), letter.text());
        assertFalse(LEFTOVER.matcher(letter.text()).find(), "в тексте остались неподставленные поля: " + letter.text());
    }

    @Test
    void everySellerTypeHasAReadableName() {
        assertTrue(letters.render("seller-approved", "А", params("sellerType", "sole_proprietor")).text().contains("индивидуальный предприниматель"));
        assertTrue(letters.render("seller-approved", "А", params("sellerType", "legal_entity")).text().contains("юридическое лицо"));
    }

    @Test
    void rejectedLetterCarriesTheModeratorReasonAndTheNextApplicationTimeInMoscow() {
        Letter letter = letters.render("seller-rejected", "Анна", params("reason", "Не хватает скана паспорта", "nextApplicationAt", "2026-10-12T17:40:00Z"));

        assertEquals("Заявка продавца отклонена", letter.subject());
        assertTrue(letter.text().contains("Причина, указанная модератором:\nНе хватает скана паспорта\n"), letter.text());
        assertTrue(letter.text().contains("Подать заявку повторно можно с 12.10.2026 20:40 (МСК)."), letter.text());
        assertFalse(LEFTOVER.matcher(letter.text()).find(), letter.text());
    }

    @Test
    void returnedLetterCarriesTheComment() {
        Letter letter = letters.render("seller-returned", "Анна", params("comment", "Приложите свежую выписку"));

        assertEquals("Заявка продавца возвращена на доработку", letter.subject());
        assertTrue(letter.text().contains("Что нужно исправить:\nПриложите свежую выписку\n"), letter.text());
        assertTrue(letter.text().contains("Срока на доработку нет."), letter.text());
    }

    @Test
    void smsLoginLetterShowsTheTimeOfLoginAndNoPhoneOrCode() {
        Letter letter = letters.render("user-sms-login", "Пётр", params("loggedInAt", "2026-10-11T17:05:09.123Z"));

        assertEquals("Вход в аккаунт по коду из SMS", letter.subject());
        assertTrue(letter.text().contains("11.10.2026 20:05 (МСК) в ваш аккаунт выполнен вход по одноразовому коду из SMS"), letter.text());
        assertFalse(letter.text().matches("(?s).*\\d{6}.*"), "в письме не должно быть шестизначных чисел, похожих на код: " + letter.text());
        assertFalse(letter.text().contains("+7"), letter.text());
    }

    @Test
    void lettersOfDecisionsHoldNoPersonalDataBeyondTheName() {
        for (String template : new String[] {"seller-approved", "seller-rejected", "seller-returned"}) {
            Map<String, Object> p = switch (template) {
                case "seller-approved" -> params("sellerType", "legal_entity");
                case "seller-rejected" -> params("reason", "Причина", "nextApplicationAt", "2026-10-12T17:40:00Z");
                default -> params("comment", "Комментарий");
            };
            String text = letters.render(template, "Мария", p).text();

            assertFalse(text.contains("@"), template + ": адреса в письме быть не должно");
            assertFalse(text.contains("Мария Иванова"), template);
            assertTrue(text.contains("Мария"), template);
        }
    }

    @Test
    void valuesAreNotParsedAgainSoTextWithBracesSubstitutesNothing() {
        Letter letter = letters.render("seller-returned", "Анна", params("comment", "Верните {name} и $1 и \\n"));

        assertTrue(letter.text().contains("Верните {name} и $1 и \\n"), letter.text());
    }

    @Test
    void missingNameFallsBackToNeutralAddress() {
        assertTrue(letters.render("seller-returned", "  ", params("comment", "x")).text().startsWith("Здравствуйте, пользователь!"));
        assertTrue(letters.render("seller-returned", null, params("comment", "x")).text().startsWith("Здравствуйте, пользователь!"));
    }

    @Test
    void validateRejectsUnknownTemplateAndWrongKeys() {
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-paid", params()));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("seller-returned", params()));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("seller-returned", params("comment", "x", "extra", "y")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("seller-returned", params("note", "x")));
    }

    @Test
    void validateRejectsValuesOfWrongKind() {
        assertThrows(InvalidNotificationException.class, () -> letters.validate("seller-returned", params("comment", " ")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("seller-returned", params("comment", 5)));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("seller-returned", params("comment", "я".repeat(1001))));
        letters.validate("seller-returned", params("comment", "я".repeat(1000)));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("user-sms-login", params("loggedInAt", "вчера")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("seller-approved", params("sellerType", "ooo")));
    }

    @Test
    void errorMessagesNameTheFieldButNeverTheValue() {
        InvalidNotificationException e = assertThrows(InvalidNotificationException.class,
                () -> letters.validate("seller-approved", params("sellerType", "секретное значение")));

        assertTrue(e.getMessage().contains("sellerType"), e.getMessage());
        assertFalse(e.getMessage().contains("секретное"), e.getMessage());
    }

    @Test
    void timeIsShownInTheConfiguredZone() {
        Letters utc = new Letters(ZoneId.of("UTC"), "UTC");

        assertTrue(utc.render("user-sms-login", "А", params("loggedInAt", "2026-10-11T17:05:09Z")).text().contains("11.10.2026 17:05 (UTC)"));
    }
}
