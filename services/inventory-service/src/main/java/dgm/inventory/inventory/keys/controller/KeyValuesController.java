package dgm.inventory.inventory.keys.controller;

import dgm.inventory.inventory.crypto.KeyValuesDocument;
import dgm.inventory.inventory.crypto.OpenedKey;
import dgm.inventory.inventory.keys.service.KeyValuesService;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getKeyValues} (OP-38): расшифрованные значения ключей заказа для письма. Вызывает только {@code delivery-service}; список
 * вызывающих проверяет фильтр каркаса по сертификату клиента и правилам маршрутов (U17). Ответ с {@code Cache-Control: no-store}, тело
 * в журналы не пишется, значения стираются сразу после сборки тела.
 */
@RestController
public class KeyValuesController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final KeyValuesService service;

    public KeyValuesController(KeyValuesService service) {
        this.service = service;
    }

    /**
     * Тело пишется в ответ напрямую, минуя преобразователи Spring MVC: на уровне TRACE они записывают в журнал всё тело ответа, а значение
     * ключа в журнале быть не должно (ST-01).
     */
    @GetMapping("/internal/v1/keys")
    public void values(@RequestParam(name = "orderId", required = false) String orderId, HttpServletResponse response) throws IOException {
        UUID order = orderId(orderId);
        List<OpenedKey> values = service.valuesOf(order);
        byte[] body;
        try {
            body = KeyValuesDocument.json(order, values).getBytes(StandardCharsets.UTF_8);
        } finally {
            KeyValuesService.destroy(values);
        }
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            response.flushBuffer();
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static UUID orderId(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Параметр orderId обязателен и должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }
}
