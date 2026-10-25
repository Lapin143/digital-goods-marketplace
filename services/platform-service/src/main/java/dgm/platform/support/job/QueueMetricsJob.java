package dgm.platform.support.job;

import dgm.platform.support.service.QueueMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Обновление метрик очереди поддержки раз в 15 секунд от конца предыдущего прохода (Р-163). Ошибка одного прохода (например, база
 * недоступна) расписание не останавливает: метрики остаются прежними, следующий проход их исправит.
 */
@Component
public final class QueueMetricsJob {

    private static final Logger LOG = LoggerFactory.getLogger(QueueMetricsJob.class);

    private final QueueMetrics metrics;

    public QueueMetricsJob(QueueMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${dgm.support.queue-metrics-interval:PT15S}", initialDelayString = "${dgm.support.queue-metrics-initial-delay:PT5S}")
    public void run() {
        try {
            metrics.refresh();
        } catch (RuntimeException e) {
            LOG.warn("Метрики очереди поддержки не обновлены: {}", e.getClass().getSimpleName());
        }
    }
}
