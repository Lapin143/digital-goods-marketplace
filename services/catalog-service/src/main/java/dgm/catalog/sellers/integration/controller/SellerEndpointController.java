package dgm.catalog.sellers.integration.controller;

import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.catalog.sellers.integration.service.IntegrationService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренняя операция {@code getSellerEndpoint} (OP-97, ADR-028): адрес, статус и версия секрета продавца для {@code delivery-service}
 * (запрос ключа продавцу) и {@code platform-service} (webhook). Вызывают только они по mTLS, список вызывающих проверяет фильтр каркаса.
 * Секрета в ответе нет: вызывающий считает его сам из {@code seller_webhook_master} по идентификатору продавца и версии (Р-187).
 *
 * <p>Подключения нет, оно отключено, продавец не одобрен или идентификатор не того вида: один и тот же 404 без причины.
 */
@RestController
public class SellerEndpointController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final IntegrationService service;

    public SellerEndpointController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping("/internal/v1/sellers/{sellerId}/integration")
    public ResponseEntity<String> endpoint(@PathVariable("sellerId") String sellerId) {
        if (!ID.matcher(sellerId).matches()) {
            throw notFound();
        }
        SellerIntegration integration = service.activeEndpoint(UUID.fromString(sellerId)).orElseThrow(SellerEndpointController::notFound);
        return ResponseEntity.ok().contentType(IntegrationController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(IntegrationViews.endpoint(integration)));
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Подключение системы продавца не найдено.");
    }
}
