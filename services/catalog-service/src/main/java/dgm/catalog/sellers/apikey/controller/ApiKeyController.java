package dgm.catalog.sellers.apikey.controller;

import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.catalog.sellers.apikey.domain.ApiKeyForm;
import dgm.catalog.sellers.apikey.domain.ApiKeyPage;
import dgm.catalog.sellers.apikey.domain.ApiKeyStatus;
import dgm.catalog.sellers.apikey.domain.IssuedKey;
import dgm.catalog.sellers.apikey.service.ApiKeyService;
import dgm.kit.idempotency.IdempotencyFilter;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.CurrentUser;
import dgm.kit.web.QueryParams;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-ключи продавца в кабинете: {@code createApiKey}, {@code listApiKeys}, {@code revokeApiKey} (OP-90 – OP-92). Только по токену: у этих
 * операций нет схемы {@code sellerApiKey}, и ключом выпустить или отозвать ключ нельзя (Р-185). Роль, область {@code seller.catalog} и второй
 * фактор проверяет фильтр каркаса по правилам маршрутов, ключ идемпотентности ведёт охранник каркаса. Профиль продавца берётся из claim
 * {@code seller_id} токена (Р-18).
 */
@RestController
public class ApiKeyController {

    static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    static final String BASE = "/api/v1/seller/api-keys";
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern STATUS = Pattern.compile("^(active|revoked)$");

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @PostMapping(BASE)
    public ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        AuthenticatedUser user = CurrentUser.of(request);
        UUID sellerId = CurrentUser.sellerId(user);
        UUID actorId = CurrentUser.id(user);
        ApiKeyForm form = ApiKeyForms.readRequest(request);
        IssuedKey issued = service.issue(sellerId, actorId, form);
        // Р-198: клиент первого запроса видит значение, а охранник идемпотентности сохраняет для повтора ответ без него
        request.setAttribute(IdempotencyFilter.REPLAY_BODY_ATTRIBUTE, Json.write(ApiKeyViews.issued(issued.key(), null)));
        return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, BASE).body(Json.write(ApiKeyViews.issued(issued.key(), issued.value())));
    }

    @GetMapping(BASE)
    public ResponseEntity<String> list(HttpServletRequest request) {
        UUID sellerId = CurrentUser.sellerId(CurrentUser.of(request));
        QueryParams params = QueryParams.of(request, "status");
        int limit = params.limit();
        Optional<ApiKeyStatus> status = params.matching("status", STATUS, 7).flatMap(ApiKeyStatus::fromCode);
        Optional<ApiKeyPage.After> after = cursor(params);
        params.check();
        ApiKeyPage page = service.list(sellerId, status, limit, after);
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(ApiKeyViews.page(page)));
    }

    @PostMapping(BASE + "/{apiKeyId}/revocation")
    public ResponseEntity<String> revoke(@PathVariable("apiKeyId") String apiKeyId, HttpServletRequest request) {
        AuthenticatedUser user = CurrentUser.of(request);
        UUID sellerId = CurrentUser.sellerId(user);
        UUID actorId = CurrentUser.id(user);
        ApiKey revoked = service.revoke(sellerId, actorId, keyId(apiKeyId));
        return ResponseEntity.ok().contentType(JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Json.write(ApiKeyViews.key(revoked)));
    }

    private static UUID keyId(String value) {
        // Идентификатор не того вида не может быть чужим ключом: тот же 404, что у несуществующего (U19), а не 400
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "API-ключ не найден.");
        }
        return UUID.fromString(value);
    }

    private static Optional<ApiKeyPage.After> cursor(QueryParams params) {
        Optional<Map<String, String>> values = params.cursorValues("c", "i");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ApiKeyPage.After(Instant.parse(values.get().get("c")), UUID.fromString(values.get().get("i"))));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            params.error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }
}
