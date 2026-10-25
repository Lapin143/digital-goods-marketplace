package dgm.platform.auditadmin.service;

import dgm.platform.auditadmin.domain.PlatformParameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Данные события {@code config.changed} по схеме AsyncAPI {@code ConfigChangedData}: схема закрыта, значения параметров не персональные.
 * Событие идёт в сжатую тему {@code platform.config}: ключ записи Kafka, {@code subject} конверта и ключ параметра это одно и то же.
 */
public final class ConfigEvents {

    public static final String TOPIC = "platform.config";
    public static final String AGGREGATE = "Parameter";
    public static final String CHANGED = "config.changed";
    public static final int SCHEMA_VERSION = 1;
    /** Идентификатор исполнителя у значения, которое никто не менял (начальный засев): действие системы, как у решений без человека. */
    public static final UUID SYSTEM = new UUID(0L, 0L);

    private ConfigEvents() {
    }

    /** {@code changedBy} это администратор, а у начального значения система. */
    public static Map<String, Object> changed(PlatformParameter parameter) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", parameter.key());
        data.put("value", parameter.value());
        data.put("unit", parameter.unit());
        data.put("version", parameter.version());
        data.put("changedBy", (parameter.updatedBy() == null ? SYSTEM : parameter.updatedBy()).toString());
        return data;
    }
}
