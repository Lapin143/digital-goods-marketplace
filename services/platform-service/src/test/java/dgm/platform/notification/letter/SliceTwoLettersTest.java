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
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Шесть писем среза 2 (Р-138, Р-153): тексты, суммы, ссылка на заказ, отказ неверных параметров, нет ключей и адресов (INV-44, INV-20). */
@Tag("TC-610")
class SliceTwoLettersTest {

    private static final Pattern LEFTOVER = Pattern.compile("\\{[A-Za-z]+}");
    private static final String ORDER = "0199c2a4-5e10-7a3b-9c1d-2f4e6a8b0c1d";
    private static final char NBSP = ' ';

    private final Letters letters = new Letters(ZoneId.of("Europe/Moscow"), "МСК", "https://shop.example/");

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void theSetHoldsTheFourLettersOfSliceOneAndTheSixOfSliceTwo() {
        assertEquals(Set.of("seller-approved", "seller-rejected", "seller-returned", "user-sms-login", "order-created", "order-paid",
                "order-refunded", "payment-refund-escalated", "delivery-failed", "delivery-overdue"), letters.codes());
    }

    @Test
    void orderCreatedHasTheNumberTheSumTheDeadlineAndTheLinkWithoutADoubleSlash() {
        Letter letter = letters.render("order-created", "Анна",
                params("orderNumber", "1042", "total", "149900 RUB", "orderId", ORDER, "payUntil", "2026-10-03T12:12:02.000Z"));

        assertEquals("Заказ создан и ждёт оплаты", letter.subject());
        assertTrue(letter.text().startsWith("Здравствуйте, Анна!"), letter.text());
        assertTrue(letter.text().contains("Заказ №1042 на сумму 1" + NBSP + "499,00" + NBSP + "₽ создан и ждёт оплаты."), letter.text());
        assertTrue(letter.text().contains("Оплатить его нужно до 03.10.2026 15:12 (МСК)."), letter.text());
        assertTrue(letter.text().contains("https://shop.example/account/orders/" + ORDER + "\n"), letter.text());
        assertFalse(letter.text().contains("//account"), letter.text());
        assertFalse(LEFTOVER.matcher(letter.text()).find(), letter.text());
    }

    @Test
    void orderPaidHasTheProductTheSumAndPromisesTheKeyInAnotherLetter() {
        Letter letter = letters.render("order-paid", "Анна", params("orderNumber", "1042", "productTitle", "Лунная тропа: ключ Steam",
                "total", "149900 RUB"));

        assertEquals("Заказ оплачен", letter.subject());
        assertTrue(letter.text().contains("Оплата заказа №1042 получена: Лунная тропа: ключ Steam, сумма 1" + NBSP + "499,00" + NBSP + "₽."),
                letter.text());
        assertTrue(letter.text().contains("Ключ придёт отдельным письмом"), letter.text());
        assertFalse(LEFTOVER.matcher(letter.text()).find(), letter.text());
    }

    @Test
    void orderRefundedNamesTheReasonInWords() {
        Letter late = letters.render("order-refunded", "Анна", params("orderNumber", "1042", "total", "149900 RUB", "refundReason", "late_payment"));
        Letter timeout = letters.render("order-refunded", "Анна", params("orderNumber", "1042", "total", "149900 RUB",
                "refundReason", "seller_api_timeout"));
        Letter dispute = letters.render("order-refunded", "Анна", params("orderNumber", "1042", "total", "149900 RUB",
                "refundReason", "dispute_decision"));

        assertEquals("Деньги за заказ возвращены", late.subject());
        assertTrue(late.text().contains("По заказу №1042 мы вернули вам 1" + NBSP + "499,00" + NBSP + "₽. Причина: оплата пришла после того"),
                late.text());
        assertTrue(timeout.text().contains("система продавца не ответила вовремя"), timeout.text());
        assertTrue(dispute.text().contains("решение по вашему спору"), dispute.text());
    }

    @Test
    void refundEscalationLetterForTheAdminNamesTheOrderTheSumAndTheAttempts() {
        Letter letter = letters.render("payment-refund-escalated", "Админ", params("orderId", ORDER, "amount", "149900 RUB", "attempts", "5"));

        assertEquals("Возврат денег не удался, нужна ручная обработка", letter.subject());
        assertTrue(letter.text().contains("по заказу " + ORDER + " на сумму 1" + NBSP + "499,00" + NBSP + "₽ не удался после 5 попыток"), letter.text());
        assertTrue(letter.text().contains("очереди ручных возвратов"), letter.text());
    }

    @Test
    void deliveryFailedLetterForTheAdminNamesTheReason() {
        Letter exhausted = letters.render("delivery-failed", "Админ", params("orderId", ORDER, "reason", "attempts_exhausted", "attempts", "6"));
        Letter rejected = letters.render("delivery-failed", "Админ", params("orderId", ORDER, "reason", "address_rejected", "attempts", "1"));

        assertEquals("Ключ не доставлен покупателю", exhausted.subject());
        assertTrue(exhausted.text().contains("закончилась ошибкой: письмо не принято после всех попыток отправки (попыток отправки: 6)"), exhausted.text());
        assertTrue(rejected.text().contains("адрес покупателя отклонён почтовым провайдером"), rejected.text());
    }

    @Test
    void deliveryOverdueLetterShowsThePaymentTimeAndTheThresholdInMinutes() {
        Letter letter = letters.render("delivery-overdue", "Админ", params("orderId", ORDER, "paidAt", "2026-10-03T12:03:41.000Z", "threshold", "1800"));

        assertEquals("Ключ не доставлен вовремя", letter.subject());
        assertTrue(letter.text().contains("оплачен 03.10.2026 15:03 (МСК), но ключ не доставлен покупателю за 30 мин"), letter.text());
    }

    @Test
    void sumsAreFormattedFromMinorUnits() {
        assertEquals("0" + ",05" + NBSP + "₽", sum("5 RUB"));
        assertEquals("0,99" + NBSP + "₽", sum("99 RUB"));
        assertEquals("1,00" + NBSP + "₽", sum("100 RUB"));
        assertEquals("999,99" + NBSP + "₽", sum("99999 RUB"));
        assertEquals("1" + NBSP + "000,00" + NBSP + "₽", sum("100000 RUB"));
        assertEquals("1" + NBSP + "234" + NBSP + "567,89" + NBSP + "₽", sum("123456789 RUB"));
        assertEquals("10,00" + NBSP + "USD", sum("1000 USD"));
    }

    private String sum(String total) {
        Letter letter = letters.render("order-paid", "А", params("orderNumber", "1", "productTitle", "Товар", "total", total));
        String text = letter.text();
        int from = text.indexOf("сумма ") + "сумма ".length();
        return text.substring(from, text.indexOf('.', text.indexOf(',', from)));
    }

    @Test
    void wrongParametersAreRejectedBeforeTheQueue() {
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-paid",
                params("orderNumber", "1042", "productTitle", "Товар", "total", "149900")), "нет валюты");
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-paid",
                params("orderNumber", "-1", "productTitle", "Товар", "total", "100 RUB")), "номер со знаком");
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-paid",
                params("orderNumber", "1", "productTitle", "Товар", "total", "1.5 RUB")), "дробная сумма");
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-created",
                params("orderNumber", "1", "total", "100 RUB", "orderId", "не-uuid", "payUntil", "2026-10-03T12:12:02Z")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-created",
                params("orderNumber", "1", "total", "100 RUB", "orderId", ORDER, "payUntil", "завтра")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-refunded",
                params("orderNumber", "1", "total", "100 RUB", "refundReason", "other")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("delivery-failed",
                params("orderId", ORDER, "reason", "other", "attempts", "1")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("delivery-overdue",
                params("orderId", ORDER, "paidAt", "2026-10-03T12:03:41Z", "threshold", "полчаса")));
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-paid", params("orderNumber", "1")), "не хватает полей");
        assertThrows(InvalidNotificationException.class, () -> letters.validate("order-paid", params("orderNumber", "1", "productTitle", "Товар",
                "total", "100 RUB", "address", "a@b.example")), "лишнее поле");
    }

    @Test
    void productTitleWithBracesIsNotSubstitutedAgain() {
        Letter letter = letters.render("order-paid", "{name}", params("orderNumber", "1", "productTitle", "Игра {total}", "total", "100 RUB"));

        assertTrue(letter.text().contains("Игра {total}"), letter.text());
        assertTrue(letter.text().startsWith("Здравствуйте, {name}!"), letter.text());
    }

    @Test
    void noLetterOfTheSliceMentionsAKeyValue() {
        UUID order = UUID.fromString(ORDER);
        for (Letter letter : new Letter[] {
                letters.render("order-created", "А", params("orderNumber", "1", "total", "100 RUB", "orderId", order.toString(),
                        "payUntil", "2026-10-03T12:12:02Z")),
                letters.render("order-paid", "А", params("orderNumber", "1", "productTitle", "Товар", "total", "100 RUB")),
                letters.render("order-refunded", "А", params("orderNumber", "1", "total", "100 RUB", "refundReason", "late_payment"))}) {
            assertFalse(letter.text().matches("(?s).*[A-Z0-9]{4,}-[A-Z0-9]{4,}-[A-Z0-9]{4,}.*"), "в письме не должно быть похожего на ключ: " + letter.text());
        }
    }

    @Test
    void defaultSiteUrlIsTheStandOrigin() {
        Letter letter = new Letters(ZoneId.of("Europe/Moscow"), "МСК").render("order-created", "А", params("orderNumber", "1", "total", "100 RUB",
                "orderId", ORDER, "payUntil", "2026-10-03T12:12:02Z"));

        assertTrue(letter.text().contains(Letters.DEFAULT_SITE_URL + "/account/orders/" + ORDER), letter.text());
    }
}
