package dgm.order.orders.controller;

import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Timestamps;
import dgm.order.orders.domain.AddressUpdate;
import dgm.order.orders.service.DeliveryAddressService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренние вызовы заказов по mTLS: {@code updateDeliveryAddress} (OP-72, вызывает только {@code platform-service}, SEQ-04). Ключ идемпотентности
 * ведёт охранник каркаса в транзакции модуля заказов: изменение, событие и сохранённый ответ фиксируются вместе, повтор с тем же ключом получает ответ
 * без записи. Другой ключ это новая повторная отправка (так оператор повторяет её после неудачи).
 */
@RestController
public class InternalOrderController {

    private static final String BASE = "/internal/v1/orders";
    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final DeliveryAddressService addresses;

    public InternalOrderController(DeliveryAddressService addresses) {
        this.addresses = addresses;
    }

    @PostMapping(BASE + "/{orderId}/update-delivery-address")
    public ResponseEntity<String> updateDeliveryAddress(@PathVariable("orderId") String orderId, HttpServletRequest request) throws IOException {
        UUID id = orderId(orderId);
        UpdateAddressBodies.UpdateRequest body = UpdateAddressBodies.read(request);
        AddressUpdate update = addresses.update(id, body.ticketId(), body.email());
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(toJson(update)));
    }

    /** Ответ по схеме UpdatedDeliveryAddress. */
    static Map<String, Object> toJson(AddressUpdate update) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", "email");
        delivery.put("address", update.address());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", update.orderId().toString());
        body.put("delivery", delivery);
        body.put("updatedAt", Timestamps.format(update.updatedAt()));
        return body;
    }

    private static UUID orderId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор заказа должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }
}
