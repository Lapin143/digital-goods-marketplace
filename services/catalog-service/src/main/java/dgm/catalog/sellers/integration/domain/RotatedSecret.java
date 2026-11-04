package dgm.catalog.sellers.integration.domain;

/**
 * Результат смены секрета подписи.
 *
 * @param secretVersion новая версия секрета
 * @param secret        новый секрет подписи, показывается один раз
 */
public record RotatedSecret(int secretVersion, String secret) {

    @Override
    public String toString() {
        return "RotatedSecret[secretVersion=" + secretVersion + ", secret=скрыт]";
    }
}
