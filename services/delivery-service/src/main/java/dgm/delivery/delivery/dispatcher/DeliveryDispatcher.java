package dgm.delivery.delivery.dispatcher;

import dgm.delivery.delivery.client.EmailClient;
import dgm.delivery.delivery.client.KeyClient;
import dgm.delivery.delivery.client.KeyRequestException;
import dgm.delivery.delivery.domain.AttemptResult;
import dgm.delivery.delivery.domain.DeliveryAddress;
import dgm.delivery.delivery.domain.QueuedDelivery;
import dgm.delivery.delivery.domain.ReleasedKeys;
import dgm.delivery.delivery.letter.KeyCountMismatchException;
import dgm.delivery.delivery.letter.Letter;
import dgm.delivery.delivery.letter.LetterBuilder;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.delivery.delivery.service.DeliveryOutcomes;
import dgm.delivery.delivery.service.DeliverySettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Диспетчер отправки (ADR-011, компонент {@code delivery-dispatcher}): раз в секунду берёт в аренду до 20 выдач, срок которых наступил
 * ({@code FOR UPDATE SKIP LOCKED}, два экземпляра сервиса одну выдачу не возьмут), и для каждой <b>вне транзакции</b> (правило 9) запрашивает ключи
 * у {@code inventory-service}, собирает письмо и передаёт провайдеру. Исход записывается короткой транзакцией после вызовов.
 *
 * <p>Правило падения («хотя бы один раз»): процесс упал после ответа провайдера и до записи исхода; аренда истекает, выдача наступает снова,
 * письмо уйдёт ещё раз с тем же идентификатором сообщения. Дубль письма допустим, потеря нет. Ключи при повторе те же (резерв закреплён).
 *
 * <p>Значения ключей живут только внутри {@link #send}: закрытие {@link ReleasedKeys} стирает их, в журнал, метрики и таблицы они не попадают.
 */
public final class DeliveryDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryDispatcher.class);

    private final DeliveryRepository deliveries;
    private final KeyClient keys;
    private final LetterBuilder letters;
    private final EmailClient email;
    private final DeliveryOutcomes outcomes;
    private final DeliverySettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public DeliveryDispatcher(DeliveryRepository deliveries, KeyClient keys, LetterBuilder letters, EmailClient email, DeliveryOutcomes outcomes,
                              DeliverySettings settings, Clock clock, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.keys = keys;
        this.letters = letters;
        this.email = email;
        this.outcomes = outcomes;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${dgm.delivery.dispatcher.interval:PT1S}", initialDelayString = "${dgm.delivery.dispatcher.initial-delay:PT5S}")
    void scheduled() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            LOG.error("Проход диспетчера выдачи завершился ошибкой, повторится в следующий раз", e);
        }
    }

    /**
     * Один проход (и для тестов).
     *
     * @return сколько выдач взято в работу
     */
    public int runOnce() {
        List<QueuedDelivery> due = deliveries.claimDue(clock.instant(), settings.lease(), settings.batch());
        due.stream().sorted(Comparator.comparing(QueuedDelivery::id)).forEach(this::dispatch);
        return due.size();
    }

    private void dispatch(QueuedDelivery delivery) {
        try {
            Instant startedAt = clock.instant();
            AttemptResult result = send(delivery);
            outcomes.recordAttempt(delivery, result, startedAt);
        } catch (RuntimeException e) {
            // исход не записан: выдача остаётся «в очереди» с арендой и наступит снова, когда аренда истечёт
            LOG.error("Попытка {} выдачи {} не завершена, повторится после аренды", delivery.nextAttemptNo(), delivery.id(), e);
        }
    }

    private AttemptResult send(QueuedDelivery delivery) {
        if (DeliveryAddress.isAnonymized(delivery.address())) {
            // пользователь обезличен (INV-44): письмо уйти не может, ключи не запрашиваем
            return new AttemptResult.Permanent("address_anonymized");
        }
        ReleasedKeys released;
        try {
            released = keys.keysOf(delivery.orderId());
        } catch (KeyRequestException e) {
            return new AttemptResult.Retryable(e.code());
        }
        try (released) {
            Letter letter = letters.build(delivery.productTitle(), delivery.quantity(), released);
            return email.send(delivery.id(), delivery.address(), letter);
        } catch (KeyCountMismatchException e) {
            // ошибка данных, а не штатный сбой (INV-19): письмо не уходит, попытка потрачена, оповещение о нарушении согласованности
            LOG.error("Число ключей заказа {} не сходится: в заказе {}, получено {}", delivery.orderId(), e.expected(), e.actual());
            meters.counter("delivery_anomalies_total", "reason", "key_count_mismatch").increment();
            return new AttemptResult.Retryable("key_count_mismatch");
        }
    }
}
