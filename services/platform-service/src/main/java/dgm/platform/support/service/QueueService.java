package dgm.platform.support.service;

import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.QueueFilter;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.StaffTicketPage;
import dgm.platform.support.repository.SupportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Очередь оператора (US-7.2, Р-163): {@code listSupportTicketQueue}, {@code getStaffSupportTicket}, {@code takeSupportTicket},
 * {@code releaseSupportTicket}. Очередь и карточка читаются из обращений и модели чтения заказа платформы, в {@code order-service} служба не
 * ходит (ADR-017).
 *
 * <p>Взятие и возврат это один условный {@code UPDATE} (SM-07/T2, T3, правило 15): два оператора, взявшие обращение одновременно, получают
 * один 200 и один 409, потому что второе обновление ждёт блокировку строки и после неё не находит статус «создано». Запись аудита
 * ({@code support.ticket_taken}, {@code support.ticket_released}) пишется в Outbox той же транзакции (ADR-014, INV-43): нет обновления,
 * нет записи, и наоборот. Код действия без дефиса, потому что схема {@code audit.recorded} допускает в нём только строчные буквы, точки и
 * подчёркивания.
 *
 * <p>Метрика {@code support_queue_actions_total{action, result}}: {@code action} это {@code take} или {@code release}, {@code result} это
 * {@code ok}, {@code conflict}, {@code not-found} или {@code error}.
 */
public final class QueueService {

    private static final Logger LOG = LoggerFactory.getLogger(QueueService.class);
    public static final String ACTION_TAKEN = "support.ticket_taken";
    public static final String ACTION_RELEASED = "support.ticket_released";
    static final String AUDIT_OBJECT = "ticket";
    public static final String METRIC_NAME = "support_queue_actions_total";

    private final SupportRepository repository;
    private final SupportTransaction transaction;
    private final SupportOutbox outbox;
    private final Clock clock;
    private final MeterRegistry metrics;

    public QueueService(SupportRepository repository, SupportTransaction transaction, SupportOutbox outbox, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.transaction = transaction;
        this.outbox = outbox;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Страница очереди: старые выше. */
    public StaffTicketPage list(QueueFilter filter) {
        List<StaffTicket> rows = repository.findQueue(filter);
        boolean more = rows.size() > filter.limit();
        List<StaffTicket> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<QueueFilter.After> next = more
                ? Optional.of(new QueueFilter.After(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()))
                : Optional.empty();
        return new StaffTicketPage(items, filter.limit(), next);
    }

    /** Карточка обращения; нет такого: 404. */
    public StaffTicket get(UUID ticketId) {
        return repository.findStaffTicket(ticketId).orElseThrow(QueueService::notFound);
    }

    /**
     * Берёт обращение в работу (SM-07/T2).
     *
     * @throws ProblemException 404 (нет такого обращения), 409 {@code state-conflict} (обращение уже взято или решено)
     */
    public StaffTicket take(Operator operator, UUID ticketId) {
        return run("take", () -> transaction.execute(() -> {
            Instant now = clock.instant();
            if (!repository.takeTicket(ticketId, operator.id(), now)) {
                throw refusal(ticketId, "Обращение уже взято в работу или решено.");
            }
            StaffTicket ticket = reread(ticketId);
            outbox.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, ticketId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    AuditRecords.staffAction(now, operator.id(), Operator.ROLE, operator.ip(), ACTION_TAKEN, AUDIT_OBJECT, ticketId.toString(),
                            List.of(AuditRecords.change("status", "created", "in_progress"),
                                    AuditRecords.change("operatorId", null, operator.id().toString()),
                                    AuditRecords.change("orderId", null, ticket.orderId().toString()))));
            LOG.info("Обращение {} по заказу {} взято в работу оператором {}", ticketId, ticket.orderId(), operator.id());
            return ticket;
        }));
    }

    /**
     * Возвращает обращение в очередь (SM-07/T3): вернуть может оператор, у которого обращение в работе.
     *
     * @throws ProblemException 404 (нет такого обращения), 409 {@code state-conflict} (обращение не в работе у этого оператора)
     */
    public StaffTicket release(Operator operator, UUID ticketId) {
        return run("release", () -> transaction.execute(() -> {
            Instant now = clock.instant();
            if (!repository.releaseTicket(ticketId, operator.id())) {
                throw refusal(ticketId, "Вернуть в очередь можно только обращение, которое вы взяли в работу.");
            }
            StaffTicket ticket = reread(ticketId);
            outbox.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, ticketId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    AuditRecords.staffAction(now, operator.id(), Operator.ROLE, operator.ip(), ACTION_RELEASED, AUDIT_OBJECT, ticketId.toString(),
                            List.of(AuditRecords.change("status", "in_progress", "created"),
                                    AuditRecords.change("operatorId", operator.id().toString(), null),
                                    AuditRecords.change("orderId", null, ticket.orderId().toString()))));
            LOG.info("Обращение {} по заказу {} возвращено в очередь оператором {}", ticketId, ticket.orderId(), operator.id());
            return ticket;
        }));
    }

    private StaffTicket run(String action, Supplier<StaffTicket> work) {
        try {
            StaffTicket ticket = work.get();
            count(action, "ok");
            return ticket;
        } catch (ProblemException e) {
            count(action, e.type() == ProblemType.NOT_FOUND ? "not-found" : "conflict");
            throw e;
        } catch (RuntimeException e) {
            count(action, "error");
            throw e;
        }
    }

    /** Обновление не затронуло строку: либо обращения нет (404), либо оно не в том статусе или не у этого оператора (409). */
    private ProblemException refusal(UUID ticketId, String conflictDetail) {
        return repository.findStaffTicket(ticketId).isEmpty() ? notFound() : new ProblemException(ProblemType.STATE_CONFLICT, conflictDetail);
    }

    private StaffTicket reread(UUID ticketId) {
        return repository.findStaffTicket(ticketId).orElseThrow(() -> new IllegalStateException("Обновлённое обращение не читается"));
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Обращение не найдено.");
    }

    private void count(String action, String result) {
        metrics.counter(METRIC_NAME, "action", action, "result", result).increment();
    }
}
