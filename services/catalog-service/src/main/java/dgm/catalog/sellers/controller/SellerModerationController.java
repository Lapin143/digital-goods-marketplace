package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.domain.Moderator;
import dgm.catalog.sellers.domain.ReviewQueuePage;
import dgm.catalog.sellers.service.SellerApplicationService.Application;
import dgm.catalog.sellers.service.SellerModerationService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.ClientAddress;
import dgm.kit.web.QueryParams;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Заявки продавцов у модератора: {@code listSellerApplicationQueue}, {@code getSellerApplicationCard}, {@code approveSellerApplication},
 * {@code rejectSellerApplication}, {@code returnSellerApplication} (OP-11, OP-12). Роль модератора, область {@code staff.moderation} и
 * второй фактор проверяет фильтр каркаса по правилам маршрутов, ключ идемпотентности ведёт охранник каркаса.
 */
@RestController
public class SellerModerationController {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String BASE = "/api/v1/staff/seller-applications";

    private final SellerModerationService service;

    public SellerModerationController(SellerModerationService service) {
        this.service = service;
    }

    @GetMapping(BASE)
    public ResponseEntity<String> queue(HttpServletRequest request) {
        QueryParams params = QueryParams.of(request);
        int limit = params.limit();
        Optional<ReviewQueuePage.After> after = cursor(params);
        params.check();
        return ok(Json.write(ModerationViews.queue(service.queue(limit, after))));
    }

    @GetMapping(BASE + "/{sellerId}")
    public ResponseEntity<String> card(@PathVariable("sellerId") String sellerId) {
        return ok(Json.write(ModerationViews.card(service.card(sellerId(sellerId)))));
    }

    @PostMapping(BASE + "/{sellerId}/approve")
    public ResponseEntity<String> approve(@PathVariable("sellerId") String sellerId, HttpServletRequest request) {
        return decided(service.approve(moderator(request), sellerId(sellerId)));
    }

    @PostMapping(BASE + "/{sellerId}/reject")
    public ResponseEntity<String> reject(@PathVariable("sellerId") String sellerId, HttpServletRequest request) throws IOException {
        UUID id = sellerId(sellerId);
        String reason = ModerationBodies.requiredText(request, "reason");
        return decided(service.reject(moderator(request), id, reason));
    }

    @PostMapping(BASE + "/{sellerId}/return")
    public ResponseEntity<String> returnForRework(@PathVariable("sellerId") String sellerId, HttpServletRequest request) throws IOException {
        UUID id = sellerId(sellerId);
        String comment = ModerationBodies.requiredText(request, "comment");
        return decided(service.returnForRework(moderator(request), id, comment));
    }

    private ResponseEntity<String> decided(Application application) {
        return ok(Json.write(SellerViews.application(application, service.settings())));
    }

    private static ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(SellerApplicationController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static Moderator moderator(HttpServletRequest request) {
        return new Moderator(SellerApplicationController.userId(request), ClientAddress.of(request));
    }

    private static UUID sellerId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор заявки должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }

    private static Optional<ReviewQueuePage.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("p", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ReviewQueuePage.After(Instant.parse(values.get().get("p")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }
}
