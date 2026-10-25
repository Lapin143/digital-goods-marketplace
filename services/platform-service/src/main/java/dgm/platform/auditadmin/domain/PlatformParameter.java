package dgm.platform.auditadmin.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Параметр платформы (схема {@code PlatformParameter}): значение, умолчание, границы и версия, она же ETag. Значения целые положительные.
 *
 * @param updatedBy администратор, изменивший значение; {@code null} у начального значения
 */
public record PlatformParameter(String key, String description, String unit, long value, long defaultValue, long minValue, long maxValue,
                                int version, Instant updatedAt, UUID updatedBy) {
}
