package dgm.catalog.sellers.integration.domain;

import dgm.catalog.sellers.domain.FieldViolation;
import java.util.List;

/**
 * Тело запроса {@code SellerIntegrationInput}: адрес и желаемый статус. Здесь проверяется только то, что схема контракта ограничивает
 * структурно (адрес есть и не длиннее 2048 знаков, как в {@code ck_seller_integration_endpoint_url}); всё, что касается безопасности
 * адреса (схема, порт, учётные данные, диапазон адресов), решает {@code EndpointGuard} и отвечает 422 {@code endpoint-not-allowed} без
 * подробностей.
 *
 * @param endpointUrl адрес после обрезки пробелов по краям; {@code null}, если поля нет
 * @param status      желаемый статус; по умолчанию {@code active}
 */
public record IntegrationForm(String endpointUrl, IntegrationStatus status) {

    /** Предел схемы и ограничения таблицы; предел защиты адреса (300 знаков) строже и проверяется охранником. */
    public static final int MAX_URL = 2048;

    public List<FieldViolation> violations() {
        if (endpointUrl == null || endpointUrl.isEmpty()) {
            return List.of(new FieldViolation("/endpointUrl", "required", "Укажите адрес системы продавца."));
        }
        if (endpointUrl.length() > MAX_URL) {
            return List.of(new FieldViolation("/endpointUrl", "too_long", "Адрес длиннее " + MAX_URL + " знаков."));
        }
        return List.of();
    }
}
