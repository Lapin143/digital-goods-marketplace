package dgm.platform.auditadmin.api;

/**
 * Параметры платформы, которые администратор меняет без выпуска версии (FT-11.1, таблица {@code audit_admin.platform_parameter}).
 * Интерфейс модуля {@code audit_admin}: соседние модули читают параметры только через него, таблицу не трогают (decomposition.md,
 * правило 3; решение Р-10). Слушатель {@code config.changed} для остальных сервисов появится в срезе 3.
 */
public interface PlatformParameters {

    /**
     * Текущее значение целочисленного параметра.
     *
     * @param key      ключ, например {@code otp.requests-per-10-minutes}
     * @param fallback значение на случай, когда параметра нет и прочитать его не удалось (база недоступна, а в памяти ничего нет):
     *                 то же, что записано в начальном засеве
     */
    int value(String key, int fallback);
}
