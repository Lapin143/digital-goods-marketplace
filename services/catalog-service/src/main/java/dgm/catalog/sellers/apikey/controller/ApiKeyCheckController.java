package dgm.catalog.sellers.apikey.controller;

import dgm.catalog.sellers.apikey.domain.KeyCheck;
import dgm.catalog.sellers.apikey.service.ApiKeyService;
import dgm.kit.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренняя проверка ключа для шлюза: {@code checkApiKey} (OP-96, ADR-027, п. 3). Вызывает только {@code api-gateway} по mTLS (список
 * вызывающих проверяет фильтр каркаса), без {@code Idempotency-Key}: это чтение по POST, чтобы хеш не попадал в адрес и журнал доступа.
 * Хеш из тела не пишется ни в журнал, ни в трассу.
 */
@RestController
public class ApiKeyCheckController {

    private final ApiKeyService service;

    public ApiKeyCheckController(ApiKeyService service) {
        this.service = service;
    }

    @PostMapping("/internal/v1/api-keys/check")
    public ResponseEntity<String> check(HttpServletRequest request) throws IOException {
        byte[] hash = ApiKeyForms.readHash(request);
        Optional<KeyCheck> found = service.check(hash);
        return ResponseEntity.ok().contentType(ApiKeyController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(ApiKeyViews.check(found)));
    }
}
