package dgm.catalog.sellers.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Переходы SM-05, которые есть в релизе R1 (docs/03-processes/SM-05-seller-profile.md). T1 это создание профиля (исходного статуса нет),
 * T8 и T9 (блокировка) придут с R2 вместе с миграцией триггера. Список повторяет триггер {@code trg_seller_profile_status_transition}.
 */
public enum SellerTransition {
    /** Заявитель отправил заявку из черновика. */
    T2_SUBMIT(SellerStatus.DRAFT, SellerStatus.ON_REVIEW),
    /** Модератор одобрил. */
    T3_APPROVE(SellerStatus.ON_REVIEW, SellerStatus.APPROVED),
    /** Модератор отклонил. */
    T4_REJECT(SellerStatus.ON_REVIEW, SellerStatus.REJECTED),
    /** Модератор вернул на доработку. */
    T5_RETURN(SellerStatus.ON_REVIEW, SellerStatus.RETURNED),
    /** Заявитель начал исправлять отклонённую анкету. */
    T6_REOPEN(SellerStatus.REJECTED, SellerStatus.DRAFT),
    /** Заявитель отправил доработанную заявку повторно. */
    T7_RESUBMIT(SellerStatus.RETURNED, SellerStatus.ON_REVIEW);

    private final SellerStatus from;
    private final SellerStatus to;

    SellerTransition(SellerStatus from, SellerStatus to) {
        this.from = from;
        this.to = to;
    }

    public SellerStatus from() {
        return from;
    }

    public SellerStatus to() {
        return to;
    }

    public static Optional<SellerTransition> between(SellerStatus from, SellerStatus to) {
        return Arrays.stream(values()).filter(t -> t.from == from && t.to == to).findFirst();
    }
}
