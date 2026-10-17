package dgm.catalog.sellers.service;

import dgm.catalog.sellers.repository.SellerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Контроль срока рассмотрения ({@code moderation-deadline-job}, US-8.8, FT-2.1). Раз в 5 минут находит заявки на проверке, срок которых
 * вышел, и один раз ставит на них пометку {@code overdue_marked_at}; пометка увеличивает счётчик {@code moderation_overdue_total}, по нему
 * правило мониторинга оповещает администратора. Статус заявки не меняется и решения по таймеру нет (BG-05).
 *
 * <p>Лидер не нужен: пометка делается одним запросом {@code update ... where overdue_marked_at is null}, второй экземпляр увидит уже
 * поставленную пометку и не повторит её.
 */
@Component
public final class ModerationDeadlineJob {

    private static final Logger LOG = LoggerFactory.getLogger(ModerationDeadlineJob.class);

    private final SellerRepository repository;
    private final SellerTransaction transaction;
    private final Clock clock;
    private final MeterRegistry meters;

    public ModerationDeadlineJob(SellerRepository repository, SellerTransaction transaction, Clock clock, MeterRegistry meters) {
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
        this.meters = meters;
    }

    /** Вход по расписанию. Интервал и первая пауза задаются ключами {@code dgm.moderation.*} (application.yml). */
    @Scheduled(fixedDelayString = "${dgm.moderation.deadline-interval:PT5M}", initialDelayString = "${dgm.moderation.deadline-initial-delay:PT1M}")
    void scheduled() {
        try {
            runPass();
        } catch (RuntimeException e) {
            LOG.error("Контроль срока рассмотрения завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько заявок помечено просроченными в этом проходе
     */
    public int runPass() {
        List<UUID> marked = transaction.execute(() -> repository.markOverdue(clock.instant()));
        if (!marked.isEmpty()) {
            meters.counter("moderation_overdue_total", "subject", "seller_application").increment(marked.size());
            LOG.warn("Срок рассмотрения заявок продавцов вышел: {} шт., профили {}", marked.size(), marked);
        }
        return marked.size();
    }
}
