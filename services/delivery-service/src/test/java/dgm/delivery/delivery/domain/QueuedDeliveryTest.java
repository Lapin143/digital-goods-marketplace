package dgm.delivery.delivery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Выдача в очереди и исход попытки (TC-603, TC-605): номер следующей попытки, закрытый вывод, маска кода ошибки. */
@Tag("TC-603")
class QueuedDeliveryTest {

    private static final UUID ID = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID BUYER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    @Test
    void theNextAttemptFollowsTheDoneOnes() {
        QueuedDelivery delivery = new QueuedDelivery(ID, ORDER, BUYER, "primary", "anna@mail.example", "Игра", 2, 3);

        assertEquals(4, delivery.nextAttemptNo());
    }

    @Test
    void thePrintedFormHidesTheAddressAndTheTitle() {
        QueuedDelivery delivery = new QueuedDelivery(ID, ORDER, BUYER, "primary", "anna@mail.example", "Секретное название", 2, 0);

        String text = delivery.toString();

        assertFalse(text.contains("anna@mail.example"), text);
        assertFalse(text.contains("Секретное название"), text);
        assertEquals("QueuedDelivery[id=" + ID + ", orderId=" + ORDER + ", kind=primary, quantity=2, attemptNo=0]", text);
    }

    @Test
    void aDeliveryWithoutKeysOrWithANegativeNumberOfAttemptsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new QueuedDelivery(ID, ORDER, BUYER, "primary", "a@b.example", "Игра", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new QueuedDelivery(ID, ORDER, BUYER, "primary", "a@b.example", "Игра", 1, -1));
    }

    @Test
    void anErrorCodeMustFitTheTableMask() {
        assertEquals("provider_timeout", new AttemptResult.Retryable("provider_timeout").code());
        assertEquals("address_rejected", new AttemptResult.Permanent("address_rejected").code());
        assertThrows(IllegalArgumentException.class, () -> new AttemptResult.Retryable("Key 1234-5678"), "пробелы и заглавные не проходят");
        assertThrows(IllegalArgumentException.class, () -> new AttemptResult.Permanent(""));
        assertThrows(IllegalArgumentException.class, () -> new AttemptResult.Retryable("a".repeat(65)));
        assertThrows(NullPointerException.class, () -> new AttemptResult.Retryable(null));
    }

    @Test
    void theAnonymizedAddressIsRecognized() {
        assertEquals(true, DeliveryAddress.isAnonymized(DeliveryAddress.ANONYMIZED));
        assertFalse(DeliveryAddress.isAnonymized("anna@mail.example"));
    }
}
