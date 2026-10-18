package dgm.payment.payments.domain;

import java.util.Map;
import java.util.Set;

/**
 * Машина состояний платежа SM-02. Допустимы три перехода: {@code created → confirmed} (T2, шлюз подтвердил), {@code created → rejected}
 * (T3, шлюз отказал) и {@code confirmed → refunded} (T4, возврат выполнен). Истечение платёжной сессии статус не меняет: платёж остаётся
 * {@code created} на случай поздней оплаты (SM-01/T6, ADR-013). Остальные переходы запрещены; те же правила держит триггер базы
 * {@code trg_payment_status_transition}, поэтому ошибка кода не превращается в испорченные данные.
 */
public final class PaymentStateMachine {

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            Payment.CREATED, Set.of(Payment.CONFIRMED, Payment.REJECTED),
            Payment.CONFIRMED, Set.of(Payment.REFUNDED),
            Payment.REJECTED, Set.of(),
            Payment.REFUNDED, Set.of());

    private PaymentStateMachine() {
    }

    /** Переход из {@code from} в {@code to} разрешён. */
    public static boolean allowed(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** Состояние не имеет выходов: дальше платёж не меняется. */
    public static boolean terminal(String status) {
        return TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }
}
