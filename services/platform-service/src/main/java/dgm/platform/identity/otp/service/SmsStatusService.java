package dgm.platform.identity.otp.service;

import dgm.kit.problem.ProblemException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Статусы доставки SMS от провайдера (OP-06, {@code receiveSmsStatus}). Статус нужен для метрики доставляемости и оповещения о сбое
 * провайдера, на коды он не влияет. Повтор того же статуса того же сообщения не считается дважды: метка «уже учтён» живёт в Redis сутки.
 * Если Redis недоступен, статус всё равно принимается (провайдер не должен повторять запросы из-за нашего сбоя) и считается без защиты
 * от повтора: метрика допускает небольшое завышение.
 */
@Component
public final class SmsStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(SmsStatusService.class);
    private static final String PREFIX = "platform:sms:status:";
    private static final Duration DEDUPE = Duration.ofDays(1);

    private final OtpStore store;
    private final MeterRegistry meters;

    public SmsStatusService(OtpStore store, MeterRegistry meters) {
        this.store = store;
        this.meters = meters;
    }

    /**
     * Учитывает статус.
     *
     * @param messageId идентификатор SMS у провайдера
     * @param status    {@code delivered} или {@code failed}
     * @return {@code true}, если статус учтён впервые
     */
    public boolean record(String messageId, String status) {
        boolean first = true;
        try {
            first = store.markOnce(PREFIX + messageId + ":" + status, DEDUPE);
        } catch (ProblemException e) {
            LOG.warn("Redis недоступен, статус SMS учтён без защиты от повтора");
        }
        if (first) {
            meters.counter("sms_delivery_total", "status", status).increment();
            if ("failed".equals(status)) {
                LOG.warn("Провайдер сообщил о недоставленном SMS {}", messageId);
            }
        }
        return first;
    }
}
