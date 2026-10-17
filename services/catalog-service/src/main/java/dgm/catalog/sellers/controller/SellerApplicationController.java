package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.service.SellerApplicationService;
import dgm.catalog.sellers.service.SellerApplicationService.Application;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Заявка продавца для заявителя: {@code createSellerApplication}, {@code getCurrentSellerApplication},
 * {@code replaceSellerApplicationDraft}, {@code submitSellerApplication} (OP-10, OP-14). Права по матрице (роль покупателя, область
 * {@code seller.apply}, полный вход) проверяет фильтр каркаса по правилам маршрутов, ключ идемпотентности ведёт охранник каркаса.
 */
@RestController
public class SellerApplicationController {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    static final String CURRENT = "/api/v1/seller-applications/current";
    private static final Pattern IF_MATCH = Pattern.compile("^\"([0-9]{1,9})\"$");

    private final SellerApplicationService service;

    public SellerApplicationController(SellerApplicationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/seller-applications")
    public ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        UUID userId = userId(request);
        SellerForm form = SellerForms.read(request);
        Application application = service.create(userId, form);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, SellerViews.etag(application.profile())).header(HttpHeaders.LOCATION, CURRENT)
                .body(Json.write(SellerViews.application(application, service.settings())));
    }

    @GetMapping("/api/v1/seller-applications/current")
    public ResponseEntity<String> current(HttpServletRequest request) {
        Application application = service.current(userId(request));
        return ok(application);
    }

    @PutMapping("/api/v1/seller-applications/current")
    public ResponseEntity<String> replace(HttpServletRequest request) throws IOException {
        UUID userId = userId(request);
        long expectedVersion = ifMatch(request);
        SellerForm form = SellerForms.read(request);
        return ok(service.replace(userId, expectedVersion, form));
    }

    @PostMapping("/api/v1/seller-applications/current/submit")
    public ResponseEntity<String> submit(HttpServletRequest request) {
        return ok(service.submit(userId(request)));
    }

    private ResponseEntity<String> ok(Application application) {
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, SellerViews.etag(application.profile()))
                .body(Json.write(SellerViews.application(application, service.settings())));
    }

    static UUID userId(HttpServletRequest request) {
        return CurrentUser.id(CurrentUser.of(request));
    }

    /** Версия из {@code If-Match}: строгий ETag вида {@code "7"}. Нет заголовка: 428; другой вид значения: 400. */
    static long ifMatch(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.IF_MATCH);
        if (header == null || header.isBlank()) {
            throw new ProblemException(ProblemType.PRECONDITION_REQUIRED, "Для изменения нужен заголовок If-Match.");
        }
        Matcher matcher = IF_MATCH.matcher(header.strip());
        if (!matcher.matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "If-Match должен быть строгим ETag вида \"7\", как в заголовке ETag ответа.");
        }
        return Long.parseLong(matcher.group(1));
    }
}
