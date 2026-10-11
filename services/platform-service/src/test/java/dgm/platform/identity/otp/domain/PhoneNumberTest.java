package dgm.platform.identity.otp.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Номер телефона: только +7 и десять цифр (R1), маска для ответов и журнала. */
class PhoneNumberTest {

    @Test
    void parsesRussianNumbersOnly() {
        assertEquals(Optional.of(new PhoneNumber("+79001234567")), PhoneNumber.parse("+79001234567"));
        for (String bad : new String[] {"89001234567", "+7900123456", "+790012345678", "+380501234567", "+7 900 123 45 67", " +79001234567",
            "+7900123456a", "", "+79001234567\n"}) {
            assertEquals(Optional.empty(), PhoneNumber.parse(bad), bad);
        }
        assertEquals(Optional.empty(), PhoneNumber.parse(null));
        assertThrows(IllegalArgumentException.class, () -> new PhoneNumber("89001234567"));
    }

    @Test
    void maskKeepsOnlyTheOperatorCodeAndTheLastFourDigits() {
        PhoneNumber phone = new PhoneNumber("+79001234567");

        assertEquals("+7900***4567", phone.masked());
        assertEquals("+7900***4567", phone.toString(), "в журнал и сообщения об ошибках номер попадает только с маской");
        assertFalse(phone.toString().contains("1234"));
        assertTrue(phone.value().endsWith("4567"));
    }
}
