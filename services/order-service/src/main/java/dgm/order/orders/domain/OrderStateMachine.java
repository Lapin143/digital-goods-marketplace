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
 * T10 («оплачен» в «отменён») и T11 («выдан» в «возвращён») относятся к R2: в коде они описаны как запрещённые, чтобы их нельзя было
 * выполнить по ошибке, а триггер базы {@code trg_orders_status_transition} в R1 их тоже не пропускает.
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

    private static final List<Transition> R1 = List.of(
            new Transition("T2", CREATED, AWAITING_PAYMENT, Set.of()),
            new Transition("T3", CREATED, CANCELLED, Set.of(OUT_OF_STOCK)),
            new Transition("T12", CREATED, CANCELLED, Set.of(PAYMENT_SESSION_FAILED, SYSTEM_ERROR)),
            new Transition("T4", AWAITING_PAYMENT, PAID, Set.of()),
            new Transition("T5", AWAITING_PAYMENT, CANCELLED, Set.of(PAYMENT_DECLINED)),
            new Transition("T6", AWAITING_PAYMENT, CANCELLED, Set.of(RESERVATION_EXPIRED)),
            new Transition("T7", CANCELLED, PAID, Set.of()),
            new Transition("T8", CANCELLED, REFUNDED, Set.of()),
            new Transition("T9", PAID, ISSUED, Set.of()));

    /** Переходы R2: в таблице есть, выполнить их нельзя. */
    private static final List<Transition> R2 = List.of(
            new Transition("T10", PAID, CANCELLED, Set.of(CancelReason.SELLER_API_TIMEOUT)),
            new Transition("T11", ISSUED, REFUNDED, Set.of()));

    private OrderStateMachine() {
    }

    /** Все разрешённые в R1 переходы. */
    public static List<Transition> allowed() {
        return R1;
    }

    /** Переходы R2, которые код не выполняет. */
    public static List<Transition> later() {
        return R2;
    }

    /**
     * Разрешённый переход между статусами. Для отмены нужна причина: она выбирает T3 или T12, T5 или T6. Для остальных переходов причина
     * не нужна и игнорируется.
     *
     * @return переход или пусто, если в R1 такого перехода нет
     */
    public static Optional<Transition> find(String from, String to, String reason) {
        for (Transition t : R1) {
            if (t.from().equals(from) && t.to().equals(to) && (!t.isCancellation() || (reason != null && t.reasons().contains(reason)))) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Как {@link #find}, но недопустимый переход это ошибка программиста: {@link IllegalStateException}. */
    public static Transition require(String from, String to, String reason) {
        return find(from, to, reason).orElseThrow(() -> new IllegalStateException(
                "Переход заказа «" + from + "» в «" + to + "»" + (reason == null ? "" : " с причиной «" + reason + "»") + " в R1 не предусмотрен"));
    }

    /** Переход разрешён только в R2 (T10, T11). */
    public static boolean isLater(String from, String to) {
        return R2.stream().anyMatch(t -> t.from().equals(from) && t.to().equals(to));
    }
}
