package dgm.order.orders.domain;

import static dgm.order.orders.domain.CancelReason.OUT_OF_STOCK;
import static dgm.order.orders.domain.CancelReason.PAYMENT_DECLINED;
import static dgm.order.orders.domain.CancelReason.PAYMENT_SESSION_FAILED;
import static dgm.order.orders.domain.CancelReason.RESERVATION_EXPIRED;
import static dgm.order.orders.domain.CancelReason.SYSTEM_ERROR;
import static dgm.order.orders.domain.OrderStatus.AWAITING_PAYMENT;
import static dgm.order.orders.domain.OrderStatus.CANCELLED;
import static dgm.order.orders.domain.OrderStatus.CREATED;
import static dgm.order.orders.domain.OrderStatus.ISSUED;
import static dgm.order.orders.domain.OrderStatus.PAID;
import static dgm.order.orders.domain.OrderStatus.REFUNDED;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Таблица переходов заказа SM-01 (T1 – T12). Переход T1 (создание) это вставка строки в статусе «создан», поэтому в таблице его нет.
 * T10 («оплачен» в «отменён», причина «система продавца не ответила в срок») выполняется с шага 11 Ф5. T11 («выдан» в «возвращён» по решению
 * спора) относится к следующему срезу: в коде он описан как запрещённый, чтобы его нельзя было выполнить по ошибке.
 *
 * <p>Переходы «создан в отменён» и «ожидает оплаты в отменён» различаются причиной отмены: T3 (нет ключей) и T12 (платёжная сессия не
 * открылась, системная ошибка); T5 (шлюз отклонил) и T6 (резерв истёк).
 */
public final class OrderStateMachine {

    /**
     * @param id      номер перехода в SM-01
     * @param from    исходный статус
     * @param to      новый статус
     * @param reasons допустимые причины отмены; пусто, если переход не отмена
     */
    public record Transition(String id, String from, String to, Set<String> reasons) {

        public boolean isCancellation() {
            return CANCELLED.equals(to);
        }
    }

    private static final List<Transition> ALLOWED = List.of(
            new Transition("T2", CREATED, AWAITING_PAYMENT, Set.of()),
            new Transition("T3", CREATED, CANCELLED, Set.of(OUT_OF_STOCK)),
            new Transition("T12", CREATED, CANCELLED, Set.of(PAYMENT_SESSION_FAILED, SYSTEM_ERROR)),
            new Transition("T4", AWAITING_PAYMENT, PAID, Set.of()),
            new Transition("T5", AWAITING_PAYMENT, CANCELLED, Set.of(PAYMENT_DECLINED)),
            new Transition("T6", AWAITING_PAYMENT, CANCELLED, Set.of(RESERVATION_EXPIRED)),
            new Transition("T7", CANCELLED, PAID, Set.of()),
            new Transition("T8", CANCELLED, REFUNDED, Set.of()),
            new Transition("T9", PAID, ISSUED, Set.of()),
            new Transition("T10", PAID, CANCELLED, Set.of(CancelReason.SELLER_API_TIMEOUT)));

    /** Переходы, которые код ещё не выполняет: в таблице есть, выполнить их нельзя. */
    private static final List<Transition> LATER = List.of(
            new Transition("T11", ISSUED, REFUNDED, Set.of()));

    private OrderStateMachine() {
    }

    /** Все переходы, которые код выполняет (T1 – T10, без T1). */
    public static List<Transition> allowed() {
        return ALLOWED;
    }

    /** Переходы, которые код пока не выполняет (T11). */
    public static List<Transition> later() {
        return LATER;
    }

    /**
     * Разрешённый переход между статусами. Для отмены нужна причина: она выбирает T3 или T12, T5 или T6. Для остальных переходов причина
     * не нужна и игнорируется.
     *
     * @return переход или пусто, если такого разрешённого перехода нет
     */
    public static Optional<Transition> find(String from, String to, String reason) {
        for (Transition t : ALLOWED) {
            if (t.from().equals(from) && t.to().equals(to) && (!t.isCancellation() || (reason != null && t.reasons().contains(reason)))) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Как {@link #find}, но недопустимый переход это ошибка программиста: {@link IllegalStateException}. */
    public static Transition require(String from, String to, String reason) {
        return find(from, to, reason).orElseThrow(() -> new IllegalStateException(
                "Переход заказа «" + from + "» в «" + to + "»" + (reason == null ? "" : " с причиной «" + reason + "»") + " не предусмотрен"));
    }

    /** Переход описан в SM-01, но код его ещё не выполняет (T11). */
    public static boolean isLater(String from, String to) {
        return LATER.stream().anyMatch(t -> t.from().equals(from) && t.to().equals(to));
    }
}
