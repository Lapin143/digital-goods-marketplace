package dgm.inventory.inventory.keys.controller;

import dgm.inventory.inventory.keys.service.SellerKeysService;
import dgm.kit.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code storeSellerKeys} (OP-99, ADR-028): ключи, которые продавец прислал в ответ на запрос выдачи. Вызывает только {@code delivery-service}; список
 * вызывающих проверяет фильтр каркаса по сертификату клиента и правилам маршрутов, ключ идемпотентности ведёт охранник каркаса. Ответ с
 * {@code Cache-Control: no-store} и без значений ключей (INV-20).
 */
@RestController
public class SellerKeysController {

    private final SellerKeysService service;

    public SellerKeysController(SellerKeysService service) {
        this.service = service;
    }

    @PostMapping("/internal/v1/seller-keys")
    public ResponseEntity<String> store(HttpServletRequest request) throws IOException {
        SellerKeyBodies.SellerKeys body = SellerKeyBodies.read(request);
        SellerKeysService.Stored stored = service.store(body.orderId(), body.requestId(), body.keys());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", stored.orderId().toString());
        result.put("stored", stored.stored());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(result));
    }
}
