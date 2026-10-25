package dgm.payment.payments.manual;

import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.payment.payments.domain.ManualRefund;
import dgm.payment.payments.domain.RefundPayment;
import dgm.payment.payments.repository.RefundRepository;
import dgm.payment.payments.repository.RefundRepository.QueueCursor;
import dgm.payment.payments.service.PaymentTransaction;
import dgm.payment.payments.service.RefundOutcomes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/**
 * Очередь ручных возвратов администратора (OP-57, FT-6.4, US-5.8): возвраты, не прошедшие автоматически после пятой попытки, и отметка
 * «выполнен вручную». Отметка, смена статуса платежа, событие {@code payment.refunded} (с признаком {@code manual}) и запись аудита без
 * персональных данных идут одной транзакцией (INV-43, ADR-014): без любого из трёх не остаётся и остальных. Строка платежа блокируется,
 * поэтому две отметки или отметка и подтверждение шлюза идут по очереди, и вторая получает 409 {@code refund-not-allowed}: статусы не
 * меняются, второе событие и второе письмо не рождаются.
 */
public final class ManualRefundService {

    /** Код действия в журнале аудита (ADR-014). */
    static final String AUDIT_ACTION = "refund.marked_manual";
    /** Тип объекта в журнале аудита: строчными буквами, как у соседей («user», «product», «seller»); так требует и таблица журнала. */
    static final String AUDIT_OBJECT = "payment";

    /** Кто отметил возврат: идентификатор пользователя и адрес, которые попадают в аудит. */
    public record Admin(UUID id, String ip) {
    }

    /** Страница очереди: на одно значение больше размера страницы не отдаётся, следующая строка нужна только для курсора. */
    public record Page(List<ManualRefund> items, int limit, Optional<QueueCursor> next) {
    }

    /** Итог отметки. */
    public record Completed(UUID paymentId, UUID orderId, Instant refundedAt) {
    }

    private final RefundRepository refunds;
    private final RefundOutcomes outcomes;
    private final PaymentTransaction transaction;
    private final Clock clock;

    public ManualRefundService(RefundRepository refunds, RefundOutcomes outcomes, PaymentTransaction transaction, Clock clock) {
        this.refunds = refunds;
        this.outcomes = outcomes;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** Очередь: старые выше. Читается на одно значение больше размера страницы, чтобы узнать, есть ли следующая. */
    public Page queue(int limit, Optional<QueueCursor> after) {
        List<ManualRefund> rows = transaction.execute(() -> refunds.queue(after, limit + 1));
        boolean more = rows.size() > limit;
        List<ManualRefund> items = more ? rows.subList(0, limit) : rows;
        Optional<QueueCursor> next = more
                ? Optional.of(new QueueCursor(items.get(items.size() - 1).escalatedAt(), items.get(items.size() - 1).paymentId()))
                : Optional.empty();
        return new Page(List.copyOf(items), limit, next);
    }

    /**
     * Отмечает возврат выполненным вручную: {@code confirmed → refunded}, {@code payment.refunded} с {@code manual = true}, запись аудита.
     *
     * @param externalRefundId идентификатор возврата в кабинете шлюза или {@code null}
     * @throws ProblemException 404, если платежа нет; 409 {@code refund-not-allowed}, если платёж не в очереди (возврат не передавался
     *                          администратору, уже отмечен или выполнен шлюзом)
     */
    public Completed complete(Admin admin, UUID paymentId, String externalRefundId) {
        try {
            return transaction.execute(() -> completeLocked(admin, paymentId, externalRefundId));
        } catch (DuplicateKeyException e) {
            throw new ProblemException(ProblemType.REFUND_NOT_ALLOWED, "Этот идентификатор возврата уже связан с другим платежом.");
        }
    }

    private Completed completeLocked(Admin admin, UUID paymentId, String externalRefundId) {
        RefundPayment payment = refunds.lockById(paymentId).orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Платёж не найден."));
        if (!payment.refundable() || !payment.inAdminQueue()) {
            throw new ProblemException(ProblemType.REFUND_NOT_ALLOWED, payment.refunded()
                    ? "Возврат по платежу уже выполнен."
                    : "Возврат по платежу не ждёт ручного исполнения.");
        }
        Instant now = clock.instant();
        RefundPayment changed = outcomes.markRefunded(payment, externalRefundId, now, true);
        transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, paymentId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                AuditRecords.staffAction(now, admin.id(), "admin", admin.ip(), AUDIT_ACTION, AUDIT_OBJECT, paymentId.toString(),
                        List.of(AuditRecords.change("status", payment.status(), changed.status()),
                                AuditRecords.change("inAdminQueue", true, false))));
        return new Completed(paymentId, changed.orderId(), now);
    }
}
