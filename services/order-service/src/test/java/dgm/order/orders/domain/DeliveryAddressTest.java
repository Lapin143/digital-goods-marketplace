package dgm.order.orders.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DeliveryAddressTest {

    @Test
    void keepsFirstLetterAndDomain() {
        assertEquals("b***@mail.example", DeliveryAddress.mask("buyer@mail.example"));
    }

    @Test
    void oneLetterLocalPartIsStillHidden() {
        assertEquals("a***@mail.example", DeliveryAddress.mask("a@mail.example"));
    }

    @Test
    void firstLetterIsACodePointNotAChar() {
        assertEquals("😀***@mail.example", DeliveryAddress.mask("😀x@mail.example"));
    }

    @Test
    void textWithoutAtSignIsHiddenCompletely() {
        assertEquals("***", DeliveryAddress.mask("no-at-sign"));
    }

    @Test
    void emptyLocalPartIsHiddenCompletely() {
        assertEquals("***", DeliveryAddress.mask("@mail.example"));
    }
}
