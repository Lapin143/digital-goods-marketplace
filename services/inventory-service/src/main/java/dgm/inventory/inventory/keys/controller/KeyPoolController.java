package dgm.inventory.inventory.keys.controller;

import dgm.inventory.inventory.keys.domain.RejectedLine;
import dgm.inventory.inventory.keys.domain.StockInfo;
import dgm.inventory.inventory.keys.domain.UploadReport;
import dgm.inventory.inventory.keys.service.KeyPoolService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import dgm.kit.time.Timestamps;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Пул ключей продавца: {@code uploadKeyBatch}, {@code uploadKeyFile}, {@code getProductStock} (OP-36, OP-37, OP-39). Роль продавца,
 * область {@code seller.catalog} и второй фактор проверяет фильтр каркаса по правилам маршрутов, ключ идемпотентности ведёт охранник
 * каркаса. Профиль продавца берётся из claim {@code seller_id} токена (Р-18), а не из запроса. Ответы без значений ключей и с
 * {@code Cache-Control: no-store}.
 */
@RestController
public class KeyPoolController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String BASE = "/api/v1/seller/products/{productId}";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private final KeyPoolService service;

    public KeyPoolController(KeyPoolService service) {
        this.service = service;
    }

    @PostMapping(BASE + "/key-batches")
    public ResponseEntity<String> uploadBatch(@PathVariable("productId") String productId, HttpServletRequest request) throws IOException {
        UUID seller = sellerId(request);
        UUID product = productId(productId);
        return report(service.upload(seller, product, KeyBodies.batch(request)));
    }

    @PostMapping(BASE + "/key-files")
    public ResponseEntity<String> uploadFile(@PathVariable("productId") String productId, HttpServletRequest request) throws IOException {
        UUID seller = sellerId(request);
        UUID product = productId(productId);
        return report(service.upload(seller, product, KeyBodies.csv(request)));
    }

    @GetMapping(BASE + "/stock")
    public ResponseEntity<String> stock(@PathVariable("productId") String productId, HttpServletRequest request) {
        StockInfo info = service.stock(sellerId(request), productId(productId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", info.productId().toString());
        body.put("available", info.available());
        body.put("reserved", info.reserved());
        body.put("issued", info.issued());
        body.put("poolSize", info.poolSize());
        body.put("poolLimit", info.poolLimit());
        body.put("updatedAt", Timestamps.format(info.updatedAt()));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(body));
    }

    private static ResponseEntity<String> report(UploadReport report) {
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (RejectedLine line : report.rejected()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("line", line.line());
            item.put("reason", line.reason().code());
            rejected.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", report.accepted());
        body.put("rejectedTotal", report.rejectedTotal());
        body.put("rejected", rejected);
        body.put("available", report.available());
        body.put("poolLimit", report.poolLimit());
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(body));
    }

    /** Профиль продавца из токена (Р-18). */
    private static UUID sellerId(HttpServletRequest request) {
        return CurrentUser.sellerId(CurrentUser.of(request));
    }

    private static UUID productId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор товара должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }
}
