package dgm.order.orders.domain;

import java.math.BigInteger;

/** Расчёт суммы и комиссии заказа (INV-12, INV-13, conventions.md 4.5 и 4.6). Деньги в копейках, комиссия считается один раз при создании. */
public final class OrderPricing {

    /** Наибольшая сумма, безопасная для JSON: 2^53 - 1 (ограничение {@code ck_orders_amount}). */
    public static final long MAX_AMOUNT = 9_007_199_254_740_991L;
    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 10;

    private static final BigInteger BASIS = BigInteger.valueOf(10_000);
    private static final BigInteger HALF = BigInteger.valueOf(5_000);

    private OrderPricing() {
    }

    /**
     * Сумма заказа: цена-снимок, умноженная на количество (INV-12).
     *
     * @throws IllegalArgumentException количество вне 1..10, цена не больше нуля или сумма выходит за {@link #MAX_AMOUNT}
     */
    public static long total(long unitPrice, int quantity) {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Количество от " + MIN_QUANTITY + " до " + MAX_QUANTITY + " (INV-10)");
        }
        if (unitPrice < 1 || unitPrice > MAX_AMOUNT) {
            throw new IllegalArgumentException("Цена за единицу должна быть от 1 копейки до " + MAX_AMOUNT);
        }
        long amount;
        try {
            amount = Math.multiplyExact(unitPrice, (long) quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Сумма заказа выходит за допустимый предел " + MAX_AMOUNT, e);
        }
        if (amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("Сумма заказа выходит за допустимый предел " + MAX_AMOUNT);
        }
        return amount;
    }

    /**
     * Комиссия платформы: {@code (amount * rateBp + 5000) / 10000}, целочисленное деление (округление half-up до копейки, INV-13). Расчёт идёт в
     * {@link BigInteger}: произведение суммы на ставку не помещается в {@code long} (так же считает ограничение базы).
     */
    public static long commission(long amount, int rateBp) {
        if (amount < 0 || rateBp < 0 || rateBp > 10_000) {
            throw new IllegalArgumentException("Сумма не может быть отрицательной, ставка от 0 до 10000 базисных пунктов");
        }
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(rateBp)).add(HALF).divide(BASIS).longValueExact();
    }
}
