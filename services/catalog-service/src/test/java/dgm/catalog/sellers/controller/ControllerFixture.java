package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeStorage;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.service.SellerApplicationService;
import dgm.catalog.sellers.service.SellerDocumentService;
import dgm.kit.json.Json;
import dgm.kit.security.AuthenticatedUser;
import dgm.kit.security.JwtFilter;
import dgm.kit.time.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Контроллеры заявки и документов поверх настоящих сервисов и репозитория в памяти. Запросы собираются без сервера. */
final class ControllerFixture {

    static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    final ManualClock clock = new ManualClock(T0);
    final FakeSellers sellers = new FakeSellers();
    final FakeStorage storage = new FakeStorage(clock);
    final SellerApplicationService applications = new SellerApplicationService(sellers, new FakeTransaction(), clock, SellerSettings.defaults());
    final SellerDocumentService documents = new SellerDocumentService(sellers, new FakeTransaction(), storage, clock, SellerSettings.defaults());
    final SellerApplicationController applicationController = new SellerApplicationController(applications);
    final SellerDocumentController documentController = new SellerDocumentController(documents);

    static MockHttpServletRequest request(String method, String uri, UUID user) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setAttribute(JwtFilter.USER_ATTRIBUTE,
                new AuthenticatedUser(user.toString(), Set.of("seller.apply"), Set.of("buyer"), List.of("pwd"), true));
        return request;
    }

    static MockHttpServletRequest request(String method, String uri) {
        return request(method, uri, FakeSellers.USER);
    }

    static MockHttpServletRequest json(String method, String uri, String body) {
        MockHttpServletRequest request = request(method, uri);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    static Map<String, Object> body(ResponseEntity<String> response) {
        return Json.parseObject(response.getBody());
    }
}
