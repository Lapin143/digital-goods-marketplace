package dgm.delivery.delivery.answer;

import dgm.delivery.delivery.service.SellerAnswerService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
 * {@code respondSellerDelivery} (OP-101, ADR-028): ответ системы продавца на запрос выдачи, ключи или отказ. Только по API-ключу: шлюз проверяет ключ и
 * ставит личность продавца заголовком (Р-185), правила маршрута и фильтр каркаса принимают только эту область. Продавец берётся из личности, а не из
 * запроса; чужой запрос неотличим от несуществующего (404, U24). Ответ с {@code Cache-Control: no-store} и без значений ключей. Идемпотентность по
 * {@code requestId} ведёт сервис, а не охранник ключа идемпотентности.
 */
@RestController
public class SellerAnswerController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final SellerAnswerService service;

    public SellerAnswerController(SellerAnswerService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/seller-api/delivery-requests/{requestId}/answer")
    public ResponseEntity<String> answer(@PathVariable("requestId") String requestId, HttpServletRequest request) throws IOException {
        UUID seller = CurrentUser.sellerId(CurrentUser.of(request));
        if (!ID.matcher(requestId).matches()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "Запрос выдачи не найден.");
        }
        SellerAnswerService.Result result = service.answer(seller, UUID.fromString(requestId), SellerAnswerBodies.read(request));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", result.requestId().toString());
        body.put("status", result.status());
        body.put("stored", result.stored());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(body));
    }
}
