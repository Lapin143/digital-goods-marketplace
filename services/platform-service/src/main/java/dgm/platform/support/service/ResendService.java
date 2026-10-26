package dgm.platform.support.service;

import dgm.kit.client.DependencyUnavailableException;
import dgm.kit.idempotency.IdempotencyKeys;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.api.Contact;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.support.client.OrderClient;
import dgm.platform.support.client.OrderRefusedException;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.ResendAccepted;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.repository.SupportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Повторная отправка ключа по обращению ({@code resendKeyForTicket}, OP-72, US-7.3, SEQ-04): оператор ключа не видит, платформа обновляет в заказе
 * адрес актуальным e-mail учётной записи, а повторную выдачу запускает само событие {@code order.address-updated} (шаг 12 выдачи, Р-170).
 *
 * <p>Порядок (правило 9: вызов соседа не внутри транзакции, Р-165: решение оператора записывается до действия):
 * <ol>
 *   <li>Быстрые проверки по обращению и модели чтения без блокировок: обращение в работе у этого оператора (иначе 409 {@code ticket-not-in-progress}),
 *       заказ «выдан» (иначе 409 {@code state-conflict}), повторная выдача уже не запрошена и не ждёт результата (иначе 409
 *       {@code delivery-in-progress}), у покупателя есть активная учётная запись (иначе 409 {@code state-conflict}). Окончательную защиту от двух
 *       выдач в очереди держит {@code delivery-service} ({@code uq_delivery_order_id_queued}), эти проверки лишь дают быстрый отказ.</li>
 *   <li>Короткая транзакция: {@code ticket.action = resend} и {@code audit.recorded} с кодом {@code support.resend_requested} (оператор, роль, обращение,
 *       заказ, время, адрес оператора как есть, ни e-mail, ни ключа). Запись остаётся, даже если вызов не удастся; второй записи «не удалось» нет.</li>
 *   <li>Вызов {@code order-service} вне транзакции. Ключ идемпотентности вызова выведен из обращения и времени последнего события выдачи в модели
 *       чтения: двойной щелчок оператора даёт один вызов и одно событие, повтор после нового события выдачи (неудача доставки) даёт новое.</li>
 *   <li>Короткая транзакция: статус выдачи в модели чтения «в очереди» со временем решения. Событие выдачи позже этого времени (письмо принято,
 *       доставлено, не доставлено) заменит его; пока его нет, повторная отправка по заказу даёт 409 {@code delivery-in-progress}.</li>
 * </ol>
 *
 * <p>Метрика {@code support_resend_total{result}}: {@code accepted} либо код проблемы ответа ({@code ticket-not-in-progress}, {@code state-conflict},
 * {@code delivery-in-progress}, {@code request-in-progress}, {@code not-found}, {@code dependency-unavailable}), {@code error} для остального.
 */
public final class ResendService {

    private static final Logger LOG = LoggerFactory.getLogger(ResendService.class);

    /** Код действия в журнале аудита (схема допускает строчные буквы, точки и подчёркивания). */
    public static final String ACTION = "support.resend_requested";
    public static final String METRIC_NAME = "support_resend_total";
    /** Имя шага для ключа идемпотентности вызова соседа (Р-170). */
    static final String STEP = "resend";
    static final String AUDIT_OBJECT = "ticket";
    /** Источник решения в записи аудита: оператор нажал кнопку. */
    static final String SOURCE_OPERATOR = "operator";
    /** Источник решения в записи аудита: платформа сама запустила отправку после смены e-mail по решению того же оператора (BPMN-04, решение 3). */
    static final String SOURCE_EMAIL_CHANGE = "email_change";
    private static final String ISSUED = "issued";
    private static final int RETRY_AFTER_SECONDS = 5;

    private final SupportRepository repository;
    private final SupportTransaction transaction;
    private final SupportOutbox outbox;
    private final UserContacts contacts;
    private final OrderClient orders;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ResendService(SupportRepository repository, SupportTransaction transaction, SupportOutbox outbox, UserContacts contacts, OrderClient orders,
                         Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.transaction = transaction;
        this.outbox = outbox;
        this.contacts = contacts;
        this.orders = orders;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @throws ProblemException 404 (нет обращения), 409 ({@code ticket-not-in-progress}, {@code state-conflict}, {@code delivery-in-progress},
     *                          {@code request-in-progress}), 503 {@code dependency-unavailable} (сосед недоступен, повтор с тем же ключом без дублей)
     */
    public ResendAccepted resend(Operator operator, UUID ticketId) {
        return counted(() -> run(operator, ticketId, SOURCE_OPERATOR));
    }

    /**
     * Повторная отправка ключа на новый адрес сразу после смены e-mail (SEQ-04, шаг 23, Р-169): те же проверки, решение записывается тем же способом до
     * вызова, но от имени оператора, который вёл смену (адреса запроса у такой записи нет), и с источником {@code email_change}. Ключ идемпотентности
     * вызова соседа выводится так же, поэтому ручная отправка оператором сразу следом объединится с этой, а не создаст вторую выдачу.
     *
     * @return {@code true}: отправка принята; {@code false}: обращение уже не в работе у оператора, повторять некому
     * @throws ProblemException те же коды, что у {@link #resend}
     */
    public boolean resendAfterEmailChange(UUID ticketId) {
        Optional<UUID> operatorId = repository.findStaffTicket(ticketId).filter(t -> SupportTicket.IN_PROGRESS.equals(t.status()))
                .flatMap(StaffTicket::operatorId);
        if (operatorId.isEmpty()) {
            LOG.info("Автоповтор по обращению {} не запущен: оно уже не в работе у оператора", ticketId);
            return false;
        }
        counted(() -> run(new Operator(operatorId.get(), ""), ticketId, SOURCE_EMAIL_CHANGE));
        return true;
    }

    private ResendAccepted counted(Supplier<ResendAccepted> action) {
        try {
            ResendAccepted accepted = action.get();
            count("accepted");
            return accepted;
        } catch (ProblemException e) {
            count(e.type().code());
            throw e;
        } catch (RuntimeException e) {
            count("error");
            throw e;
        }
    }

    private ResendAccepted run(Operator operator, UUID ticketId, String source) {
        StaffTicket ticket = repository.findStaffTicket(ticketId).orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Обращение не найдено."));
        if (!SupportTicket.IN_PROGRESS.equals(ticket.status()) || ticket.operatorId().filter(operator.id()::equals).isEmpty()) {
            throw new ProblemException(ProblemType.TICKET_NOT_IN_PROGRESS, "Повторно отправить ключ можно только по обращению, которое вы взяли в работу.");
        }
        UUID orderId = ticket.orderId();
        OrderView view = repository.findOrderView(orderId)
                .orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT, "Данных по заказу обращения в поддержке нет, повторная отправка недоступна."));
        if (!ISSUED.equals(view.orderStatus())) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Повторно отправить ключ можно только по заказу в статусе «выдан».",
                    Map.of("currentStatus", view.orderStatus()), Map.of());
        }
        if (view.deliveryStatus().filter(OrderView.QUEUED::equals).isPresent()) {
            throw new ProblemException(ProblemType.DELIVERY_IN_PROGRESS, "Выдача по заказу ещё выполняется: дождитесь её результата.");
        }
        Contact contact = contacts.findActive(ticket.buyerId()).orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT,
                "У покупателя нет активной учётной записи, адрес для отправки неизвестен."));

        Instant decidedAt = clock.instant();
        recordDecision(operator, ticketId, orderId, decidedAt, source);

        UUID key = IdempotencyKeys.derived(STEP + "-" + view.deliveryUpdatedAt().map(at -> Long.toString(at.toEpochMilli())).orElse("first"), ticketId);
        try {
            orders.updateDeliveryAddress(orderId, ticketId, contact.email(), key);
        } catch (OrderRefusedException e) {
            throw refusal(e, ticketId, orderId);
        } catch (DependencyUnavailableException e) {
            LOG.warn("Сервис заказов недоступен при повторной отправке по обращению {} (заказ {}): {}", ticketId, orderId, e.reason());
            throw e.toProblem(RETRY_AFTER_SECONDS);
        }

        transaction.execute(() -> repository.updateDelivery(orderId, OrderView.QUEUED, decidedAt, clock.instant()));
        LOG.info("Повторная отправка по обращению {} (заказ {}) принята: оператор {}", ticketId, orderId, operator.id());
        return new ResendAccepted(ticketId, orderId);
    }

    /** Решение оператора: действие обращения и запись аудита одной короткой транзакцией, до вызова соседа (Р-165). */
    private void recordDecision(Operator operator, UUID ticketId, UUID orderId, Instant decidedAt, String source) {
        transaction.execute(() -> {
            if (!repository.markResend(ticketId, operator.id())) {
                throw new ProblemException(ProblemType.TICKET_NOT_IN_PROGRESS, "Обращение вернули в очередь или передали другому оператору.");
            }
            outbox.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, ticketId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    AuditRecords.staffAction(decidedAt, operator.id(), Operator.ROLE, operator.ip(), ACTION, AUDIT_OBJECT, ticketId.toString(),
                            List.of(AuditRecords.change("action", null, "resend"),
                                    AuditRecords.change("orderId", null, orderId.toString()),
                                    AuditRecords.change("source", null, source))));
            return null;
        });
    }

    /** Отказ соседа по смыслу в ответ платформы: что в заказе не так, а что дело самой платформы (тогда оператору 503 и повтор). */
    private static ProblemException refusal(OrderRefusedException e, UUID ticketId, UUID orderId) {
        return switch (e.status()) {
            case 404 -> new ProblemException(ProblemType.NOT_FOUND, "Заказ по обращению не найден.");
            case 409 -> "request-in-progress".equals(e.problemCode())
                    ? new ProblemException(ProblemType.REQUEST_IN_PROGRESS, "Такая же отправка по этому обращению уже выполняется.", Map.of(),
                            Map.of("Retry-After", "1"))
                    : new ProblemException(ProblemType.STATE_CONFLICT, "Повторно отправить ключ можно только по заказу в статусе «выдан».");
            default -> {
                LOG.error("Сервис заказов не принял обновление адреса по обращению {} (заказ {}): код {} {}: ошибка вызова платформы", ticketId, orderId,
                        e.status(), e.problemCode());
                yield ProblemException.dependencyUnavailable("Сервис временно недоступен, повторите запрос позже.", RETRY_AFTER_SECONDS);
            }
        };
    }

    private void count(String result) {
        metrics.counter(METRIC_NAME, "result", result).increment();
    }
}
