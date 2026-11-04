package dgm.catalog.sellers.integration.domain;

/**
 * Результат сохранения подключения.
 *
 * @param integration подключение после сохранения
 * @param secret      секрет подписи: есть только при первом подключении (версия 0 стала 1), иначе {@code null}; показывается один раз
 */
public record SavedIntegration(SellerIntegration integration, String secret) {

    @Override
    public String toString() {
        // Секрет не должен попасть в журнал приложения через строковое представление
        return "SavedIntegration[integration=" + integration + ", secret=" + (secret == null ? "нет" : "скрыт") + "]";
    }
}
