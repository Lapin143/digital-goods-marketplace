package dgm.catalog.sellers.integration.controller;

import dgm.catalog.sellers.integration.domain.IntegrationForm;
import dgm.catalog.sellers.integration.domain.RotatedSecret;
import dgm.catalog.sellers.integration.domain.SavedIntegration;
import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.catalog.sellers.integration.service.IntegrationService;
import dgm.kit.idempotency.IdempotencyFilter;
import dgm.kit.json.Json;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.web.IfMatch;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Подключение системы продавца в кабинете: {@code getSellerIntegration}, {@code setSellerIntegration},
 * {@code rotateSellerIntegrationSecret} (OP-93 – OP-95). Только по токену: схемы {@code sellerApiKey} у этих операций нет, и ключом
 * подключение не меняется (Р-185). Роль, область {@code seller.catalog} и второй фактор проверяет фильтр каркаса по правилам маршрутов,
 * ключ идемпотентности смены ведёт охранник каркаса. Профиль продавца берётся из claim {@code seller_id} токена (Р-18).
 *
 * <p>Ответы с секретом и с версией записи не кэшируются ({@code Cache-Control: no-store}); секрет уходит только в ответе первого {@code PUT}
 * и смены.
 */
@RestController
public class IntegrationController {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    static final String BASE = "/api/v1/seller/integration";

    private final IntegrationService service;

    public IntegrationController(IntegrationService service) {
        this.service = service;
    }

    @GetMapping(BASE)
    public ResponseEntity<String> get(HttpServletRequest request) {
        UUID sellerId = CurrentUser.sellerId(CurrentUser.of(request));
        Optional<SellerIntegration> found = service.get(sellerId);
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, IfMatch.etag(found.map(SellerIntegration::version).orElse(0L)))
                .body(Json.write(IntegrationViews.integration(found)));
    }

    @PutMapping(BASE)
    public ResponseEntity<String> set(HttpServletRequest request) throws IOException {
        AuthenticatedUser user = CurrentUser.of(request);
        UUID sellerId = CurrentUser.sellerId(user);
        UUID actorId = CurrentUser.id(user);
        // Порядок: заголовок, тип и тело читаются до обращения к сервису; без If-Match 428, чужой формат 400, не тот тип 415
        long expectedVersion = IfMatch.version(request);
        IntegrationForm form = IntegrationForms.read(request);
        SavedIntegration saved = service.set(sellerId, actorId, expectedVersion, form);
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, IfMatch.etag(saved.integration().version())).body(Json.write(IntegrationViews.saved(saved)));
    }

    @PostMapping(BASE + "/secret-rotation")
    public ResponseEntity<String> rotate(HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        UUID sellerId = CurrentUser.sellerId(user);
        UUID actorId = CurrentUser.id(user);
        RotatedSecret rotated = service.rotate(sellerId, actorId);
        // Р-198: клиент первого запроса видит секрет, а охранник идемпотентности сохраняет для повтора ответ без него
        request.setAttribute(IdempotencyFilter.REPLAY_BODY_ATTRIBUTE, Json.write(IntegrationViews.rotation(rotated.secretVersion(), null)));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(IntegrationViews.rotation(rotated)));
    }
}
