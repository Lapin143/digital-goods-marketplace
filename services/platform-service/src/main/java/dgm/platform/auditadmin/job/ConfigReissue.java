package dgm.platform.auditadmin.job;

import dgm.platform.auditadmin.service.ParameterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Переиздание значений параметров при старте сервиса (Р-167): после сброса одной только Kafka сжатая тема {@code platform.config} пуста,
 * а в базе значения есть, и слушатели остались бы со значениями из {@code application.yml}. Сбой переиздания старту не мешает: значения
 * параметров при этом не теряются, а следующий старт или любая правка параметра отправит их снова.
 */
@Component
public final class ConfigReissue {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigReissue.class);

    private final ParameterService parameters;

    public ConfigReissue(ParameterService parameters) {
        this.parameters = parameters;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            int reissued = parameters.reissue();
            LOG.info("Значения параметров платформы переизданы в platform.config: {}", reissued);
        } catch (RuntimeException e) {
            // Текст ошибки может нести значения, в журнал идёт только класс
            LOG.warn("Значения параметров платформы не переизданы при старте: {}", e.getClass().getSimpleName());
        }
    }
}
