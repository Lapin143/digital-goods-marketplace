package dgm.payment.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Попытка возврата денег по платежу ({@code payments.refund_attempt}, ADR-015): одна строка на попытку, номер от 1 до 5. Открытая попытка
 * ({@link #PENDING}, {@link #IN_PROGRESS}) одна на платёж и имеет срок; завершённая ({@link #PROCESSING}, {@link #SUCCEEDED},
 * {@link #FAILED}) срока не имеет.
 *
 * @param id            идентификатор попытки
 * @param paymentId     платёж, по которому выполняется возврат
 * @param attemptNo     номер попытки от 1
 * @param status        {@link #PENDING}, {@link #IN_PROGRESS}, {@link #PROCESSING}, {@link #SUCCEEDED} или {@link #FAILED}
 * @param nextAttemptAt когда попытку можно выполнить (у занятой исполнителем сдвинут на срок аренды) или {@code null} у завершённой
 * @param errorCode     короткий код отказа шлюза у неудачной попытки или {@code null}
 */
public record RefundAttempt(UUID id, UUID paymentId, int attemptNo, String status, Instant nextAttemptAt, String errorCode) {

    /** Ждёт срока. */
    public static final String PENDING = "pending";
    /** Занята исполнителем: аренда истекает в {@code nextAttemptAt}. */
    public static final String IN_PROGRESS = "in_progress";
    /** Шлюз принял возврат и выполняет его, ждём уведомление {@code refund.completed}. */
    public static final String PROCESSING = "processing";
    /** Деньги возвращены. */
    public static final String SUCCEEDED = "succeeded";
    /** Шлюз не выполнил возврат, срок следующей попытки назначен отдельной строкой. */
    public static final String FAILED = "failed";

    /** Наибольшее число попыток: ограничение базы {@code ck_refund_attempt_attempt_no}. */
    public static final int MAX_ATTEMPTS = 5;

    public RefundAttempt {
        if (attemptNo < 1 || attemptNo > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Номер попытки возврата от 1 до " + MAX_ATTEMPTS + ": " + attemptNo);
        }
    }

    /** Первая и все следующие попытки открываются сроком: {@code pending} с моментом, когда исполнитель их возьмёт. */
    public static RefundAttempt scheduled(UUID id, UUID paymentId, int attemptNo, Instant dueAt) {
        return new RefundAttempt(id, paymentId, attemptNo, PENDING, dueAt, null);
    }

    /** Попытка открыта: у неё есть срок и новая попытка по этому платежу не создаётся. */
    public boolean open() {
        return PENDING.equals(status) || IN_PROGRESS.equals(status);
    }
}
