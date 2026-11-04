package dgm.catalog.sellers.apikey.domain;

/**
 * Результат выпуска: ключ без значения и значение, которое показывается один раз. Объект живёт от сервиса до ответа клиенту и никуда не
 * сохраняется; {@link #toString()} значение скрывает, чтобы случайная запись в журнал его не выдала.
 */
public record IssuedKey(ApiKey key, String value) {

    @Override
    public String toString() {
        return "IssuedKey[key=" + key + ", value=<скрыто>]";
    }
}
