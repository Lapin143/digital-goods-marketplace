package dgm.order.orders.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Сумма заказа это цена-снимок на количество (INV-12), комиссия считается один раз по формуле базы с округлением half-up (INV-13). */
@Tag("TC-510")
class OrderPricingTest {

    @Test
    void theTotalIsThePriceTimesTheQuantity() {
        assertEquals(299_800L, OrderPricing.total(149_900, 2));
        assertEquals(149_900L, OrderPricing.total(149_900, 1));
        assertEquals(1_499_000L, OrderPricing.total(149_900, 10));
    }

    @Test
    void theQuantityIsFromOneToTen() {
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(100, 0));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(100, 11));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(100, -1));
    }

    @Test
    void thePriceIsPositiveAndWithinTheSafeRange() {
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(0, 1));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(-5, 1));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(OrderPricing.MAX_AMOUNT + 1, 1));
    }

    @Test
    void aTotalAboveTheLimitOfTheContractIsRefused() {
        assertEquals(OrderPricing.MAX_AMOUNT, OrderPricing.total(OrderPricing.MAX_AMOUNT, 1));
        long edge = OrderPricing.MAX_AMOUNT / 10;
        assertEquals(edge * 10, OrderPricing.total(edge, 10));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(edge + 1, 10));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.total(OrderPricing.MAX_AMOUNT, 2));
    }

    @Tag("TC-809")
    @Test
    void theCommissionIsRoundedHalfUpToTheKopeck() {
        assertEquals(5_996L, OrderPricing.commission(299_800, 200));
        assertEquals(2_998L, OrderPricing.commission(149_900, 200));
        assertEquals(1L, OrderPricing.commission(25, 200), "0,5 копейки округляется вверх");
        assertEquals(0L, OrderPricing.commission(24, 200), "0,48 копейки округляется вниз");
        assertEquals(3L, OrderPricing.commission(125, 200), "2,5 копейки округляется вверх");
        assertEquals(21L, OrderPricing.commission(1_025, 200), "10,25 ₽ при 2 %: 0,21 ₽ (US-8.4, сценарий 3)");
        assertEquals(25L, OrderPricing.commission(1_250, 200), "12,50 ₽ при 2 %: 0,25 ₽ (US-8.4, сценарий 3)");
    }

    @Tag("TC-809")
    @Test
    void theRateRunsFromZeroToTheWholeAmount() {
        assertEquals(0L, OrderPricing.commission(299_800, 0));
        assertEquals(299_800L, OrderPricing.commission(299_800, 10_000));
    }

    @Tag("TC-809")
    @Test
    void theLargestAmountDoesNotOverflow() {
        assertEquals(OrderPricing.MAX_AMOUNT, OrderPricing.commission(OrderPricing.MAX_AMOUNT, 10_000));
        assertEquals(180_143_985_094_820L, OrderPricing.commission(OrderPricing.MAX_AMOUNT, 200));
    }

    @Tag("TC-809")
    @Test
    void anAmountOrRateOutOfRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.commission(-1, 200));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.commission(100, -1));
        assertThrows(IllegalArgumentException.class, () -> OrderPricing.commission(100, 10_001));
    }
}
