package dgm.platform.support.service;

import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.auditadmin.api.PlatformParameters;
import dgm.platform.support.domain.OrderView;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.domain.TicketFilter;
import dgm.platform.support.domain.TicketPage;
import dgm.platform.support.repository.SupportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обращения покупателя (US-7.1, Р-177): {@code createSupportTicket}, {@code listSupportTickets}, {@code getSupportTicket}. Все проверки идут
 * по модели чтения {@code support.order_view}, в {@code order-service} платформа не ходит (ADR-017): окно 72 часа и принадлежность заказа
 * не зависят от доступности соседа.
 *
 * <p>Порядок проверок при создании: заказ принадлежит покупателю (чужой и несуществующий дают один и тот же 404, доступ по владельцу, а не
 * по номеру), заказ «выдан» (иначе 409 {@code state-conflict}), с первичной выдачи прошло не больше {@code support.window-seconds} (иначе
 * 409 {@code ticket-window-closed}; повторная отправка окно не продлевает, потому что время берётся из {@code issued_at}), открытого
 * обращения по заказу нет (INV-21: частичный уникальный индекс и {@code INSERT ... ON CONFLICT DO NOTHING}, событие {@code ticket.created}
 * пишется только при настоящей вставке). Последним идёт лимит {@code support.requests-per-hour}: в окно записывается только создание,
 * которое состоялось, поэтому повторы с отказом 409 лимит не тратят, а при отказе 429 вставка откатывается вместе с транзакцией.
 *
 * <p>Метрика {@code support_ticket_requests_total{result}}: {@code created}, {@code not-found}, {@code state-conflict},
 * {@code window-closed}, {@code already-open}, {@code rate-limited}, {@code error}.
 */
public final class TicketService {

    private static final Logger LOG = LoggerFactory.getLogger(TicketService.class);
    public static final String WINDOW_KEY = "support.window-seconds";
    public static final String RATE_KEY = "support.requests-per-hour";
    /** Значения параметров из начального засева на случай, когда прочитать их не удалось (то же, что в таблице параметров). */
    static final int WINDOW_DEFAULT_SECONDS = 259_200;
    static final int RATE_DEFAULT = 5;
    public static final String METRIC_NAME = "support_ticket_requests_total";
    private static final String ISSUED = "issued";

    private final SupportRepository repository;
    private final SupportTransaction transaction;
    private final SupportOutbox outbox;
    private final SupportRateLimiter limiter;
    private final PlatformParameters parameters;
    private final Clock clock;
    private final MeterRegistry metrics;

    public TicketService(SupportRepository repository, SupportTransaction transaction, SupportOutbox outbox, SupportRateLimiter limiter,
                         PlatformParameters parameters, Clock clock, MeterRegistry metrics) {
        this.repository = repository;
        this.transaction = transaction;
        this.outbox = outbox;
        this.limiter = limiter;
        this.parameters = parameters;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Создаёт обращение «не получил ключ».
     *
     * @throws ProblemException 404 (заказ не найден или чужой), 409 ({@code state-conflict}, {@code ticket-window-closed},
     *                          {@code ticket-already-open}), 429 с {@code Retry-After}; 503, если Redis недоступен
     */
    public SupportTicket create(UUID buyerId, UUID orderId) {
        try {
            SupportTicket ticket = transaction.execute(() -> createInTransaction(buyerId, orderId));
            count("created");
            return ticket;
        } catch (ProblemException e) {
            count(result(e.type()));
            throw e;
        } catch (RuntimeException e) {
            count("error");
            throw e;
        }
    }

    private SupportTicket createInTransaction(UUID buyerId, UUID orderId) {
        OrderView view = repository.findOrderView(orderId).filter(v -> v.buyerId().equals(buyerId))
                .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Заказ не найден."));
        Instant now = clock.instant();
        Optional<Instant> issuedAt = view.issuedAt();
        if (!ISSUED.equals(view.orderStatus()) || issuedAt.isEmpty()) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Обращение «Не получил ключ» создаётся по выданному заказу.");
        }
        Duration window = Duration.ofSeconds(parameters.value(WINDOW_KEY, WINDOW_DEFAULT_SECONDS));
        if (now.isAfter(issuedAt.get().plus(window))) {
            throw new ProblemException(ProblemType.TICKET_WINDOW_CLOSED, "С первичной выдачи прошло больше допустимого срока, обращение не принимается.");
        }
        UUID ticketId = Uuids.v7(clock);
        if (!repository.insertBuyerTicket(ticketId, orderId, buyerId, now)) {
            throw new ProblemException(ProblemType.TICKET_ALREADY_OPEN, "По заказу уже есть открытое обращение.");
        }
        Optional<Duration> wait = limiter.admit(buyerId, parameters.value(RATE_KEY, RATE_DEFAULT), now);
        if (wait.isPresent()) {
            long seconds = Math.max(1, (wait.get().toMillis() + 999) / 1000);
            throw new ProblemException(ProblemType.RATE_LIMITED, "Слишком много обращений за час, повторите позже.", Map.of(),
                    Map.of("Retry-After", Long.toString(seconds)));
        }
        outbox.publish(SupportEvents.TOPIC, SupportEvents.AGGREGATE, ticketId.toString(), SupportEvents.CREATED, 1,
                SupportEvents.created(ticketId, orderId, buyerId, SupportTicket.BUYER, SupportTicket.KEY_NOT_RECEIVED));
        LOG.info("Обращение {} по заказу {} создано покупателем", ticketId, orderId);
        return repository.findTicket(ticketId).orElseThrow(() -> new IllegalStateException("Созданное обращение не читается"));
    }

    /** Страница обращений покупателя: новые выше. */
    public TicketPage list(TicketFilter filter) {
        List<SupportTicket> rows = repository.findTickets(filter);
        boolean more = rows.size() > filter.limit();
        List<SupportTicket> items = more ? rows.subList(0, filter.limit()) : rows;
        Optional<TicketFilter.After> next = more
                ? Optional.of(new TicketFilter.After(items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).id()))
                : Optional.empty();
        return new TicketPage(items, filter.limit(), next);
    }

    /** Обращение покупателя; чужое и несуществующее дают один и тот же 404 (U1). */
    public SupportTicket get(UUID buyerId, UUID ticketId) {
        return repository.findTicket(ticketId).filter(t -> t.buyerId().equals(buyerId))
                .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Обращение не найдено."));
    }

    private static String result(ProblemType type) {
        return switch (type) {
            case NOT_FOUND -> "not-found";
            case STATE_CONFLICT -> "state-conflict";
            case TICKET_WINDOW_CLOSED -> "window-closed";
            case TICKET_ALREADY_OPEN -> "already-open";
            case RATE_LIMITED -> "rate-limited";
            default -> "error";
        };
    }

    private void count(String result) {
        metrics.counter(METRIC_NAME, "result", result).increment();
    }
}
