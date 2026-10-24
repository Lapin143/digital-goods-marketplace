package dgm.delivery.delivery.watch;

import dgm.delivery.delivery.domain.DeliveryState;
import dgm.delivery.delivery.domain.DeliveryStatus;
import dgm.delivery.delivery.domain.DueWatch;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.delivery.delivery.service.DeliveryEvents;
import dgm.delivery.delivery.service.DeliverySettings;
import dgm.delivery.delivery.service.DeliveryTransaction;
import dgm.delivery.delivery.service.StatusSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Контроль доставки 30 минут (NFT-2.4, E11, ADR-011, компонент {@code delivery-watch}): раз в 30 секунд берёт открытые контроли, срок которых
 * наступил ({@code FOR UPDATE SKIP LOCKED} в одной короткой транзакции, два экземпляра сервиса один контроль не возьмут). Первичная выдача заказа
 * не «доставлено»: контроль «открыт» переходит в «передан в поддержку», в Outbox пишется {@code delivery.overdue}, оповещение администратору и обращение
 * системы создаёт {@code platform-service} (шаг 15). Событие уходит один раз: переход и запись в Outbox в одной транзакции, а передавать контроль
 * повторно нечего. Статус выдачи контроль не меняет (SM-06). Доставка, которая подтверждена до прохода, закрывает контроль без события.
 *
 * <p>Вызовов соседей и провайдера здесь нет, поэтому весь проход укладывается в одну транзакцию.
 */
public final class DeliveryWatch {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryWatch.class);

    private final DeliveryRepository deliveries;
    private final DeliveryTransaction transaction;
    private final DeliverySettings settings;
    private final StatusSettings statusSettings;
    private final Clock clock;
    private final MeterRegistry meters;

    public DeliveryWatch(DeliveryRepository deliveries, DeliveryTransaction transaction, DeliverySettings settings, StatusSettings statusSettings, Clock clock,
                         MeterRegistry meters) {
        this.deliveries = deliveries;
        this.transaction = transaction;
        this.settings = settings;
        this.statusSettings = statusSettings;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${dgm.delivery.watch.interval:PT30S}", initialDelayString = "${dgm.delivery.watch.initial-delay:PT10S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход контроля доставки завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько контролей передано в поддержку
     */
    public int runOnce() {
        int handedOver = transaction.execute(this::handOverDue);
        if (handedOver > 0) {
            meters.counter("delivery_overdue_total").increment(handedOver);
        }
        return handedOver;
    }

    private int handOverDue() {
        Instant now = clock.instant();
        int handedOver = 0;
        for (DueWatch watch : deliveries.lockDueWatches(now, statusSettings.watchBatch())) {
            DeliveryState primary = deliveries.primaryOf(watch.orderId()).orElse(null);
            if (primary == null) {
                // контроль открывается вместе с выдачей, поэтому так быть не должно; событие без идентификатора выдачи контракт не допускает
                LOG.error("У контроля заказа {} нет первичной выдачи, передача в поддержку невозможна", watch.orderId());
                meters.counter("delivery_anomalies_total", "reason", "watch_without_delivery").increment();
                continue;
            }
            if (DeliveryStatus.DELIVERED.equals(primary.status())) {
                deliveries.closeWatch(watch.orderId(), now);
                continue;
            }
            if (deliveries.handOver(watch.orderId(), now)) {
                transaction.publish(DeliveryEvents.TOPIC, DeliveryEvents.AGGREGATE, watch.orderId().toString(), DeliveryEvents.OVERDUE,
                        DeliveryEvents.SCHEMA_VERSION, DeliveryEvents.overdue(primary.id(), watch.orderId(), watch.deadlineAt().minus(settings.controlWindow()),
                                settings.controlWindow().toSeconds()));
                LOG.warn("Заказ {}: за {} секунд выдача {} не доставлена, контроль передан в поддержку", watch.orderId(), settings.controlWindow().toSeconds(),
                        primary.id());
                handedOver++;
            }
        }
        return handedOver;
    }
}
