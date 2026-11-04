package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.json.Json;
import dgm.kit.security.JwtDecoders;
import dgm.kit.security.JwtSettings;
import dgm.kit.testing.TestTokens;
import dgm.kit.time.ManualClock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Вход по API-ключу продавца в единственном фильтре шлюза (ADR-027, Р-185, Р-186, Р-188): проверка ключа, память о ключах, лимиты
 * {@code seller.api} и {@code seller-upload}, заголовки личности. Без сети: каталог и Redis заменены {@link FakeCatalog} и
 * {@link FakeKeyCache}, часы ручные.
 */
class EntryFilterApiKeyTest {

    /** Образцы значений ключа: {@code dgm_} и 40 знаков base64url (байты 0 – 29, 1 – 30 и 2 – 31). Настоящих ключей среди них нет. */
    private static final String SAMPLE = "dgm_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd";
    private static final String OTHER_SAMPLE = "dgm_AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0e";
    private static final String THIRD_SAMPLE = "dgm_AgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4f";
    /** SHA-256 первого образца, посчитанный отдельно (sha256sum), а не кодом шлюза. */
    private static final String SAMPLE_DIGEST = "12e10c2d53ed9875ac670787e0e03dd6278959d6f50af238aea1fd2c77cd8023";

    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";
    private static final String OTHER_SELLER = "0199e0a0-0000-7000-8000-000000000102";
    private static final String KEY_ID = "0199e0a0-0000-7000-8000-000000000901";
    private static final String OTHER_KEY_ID = "0199e0a0-0000-7000-8000-000000000902";
    private static final String PRODUCT = "0199e0a0-0000-4000-8000-000000000001";
    private static final String REQUEST_ID = "0199e0a0-0000-7000-8000-000000000a01";
    private static final String SELLER_USER = "22222222-2222-4222-8222-222222222222";

    private static final String STOCK = "/api/v1/seller/products/" + PRODUCT + "/stock";
    private static final String UPLOAD = "/api/v1/seller/products/" + PRODUCT + "/key-batches";
    private static final String ANSWER = "/api/v1/seller-api/delivery-requests/" + REQUEST_ID + "/answer";
    private static final String KEYS_LIST = "/api/v1/seller/api-keys";

    private final ManualClock clock = new ManualClock(Instant.parse("2026-11-04T10:00:00Z"));
    private final TestTokens tokens = TestTokens.create(clock);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GatewayProperties properties = EntryFilterTest.properties();
    private final Limiter limiter = new Limiter();
    private final RouteTable routes = RouteTable.fromClasspath("dgm/gateway-routes.json", Set.of("catalog-service",
            "inventory-service", "order-service", "payment-service", "delivery-service", "platform-service", "keycloak", "object-storage",
            "web-app", "external-stubs"));
    private final FakeKeyCache cache = new FakeKeyCache(clock);
    private final FakeCatalog catalog = new FakeCatalog();
    private final ApiKeyVerifier keys = new ApiKeyVerifier(cache, catalog, properties, meters, clock);
    private final JwtDecoder decoder = JwtDecoders.forPublicKey(tokens.publicKey(), new JwtSettings(TestTokens.ISSUER, null), clock);
    private final EntryFilter filter = new EntryFilter(routes, new TokenVerifier(decoder), keys, limiter, properties, meters, clock, 0);

    /** Ограничитель с записью обращений и заданным ответом. */
    private static final class Limiter implements RequestLimiter {

        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile Supplier<Mono<Decision>> next = () -> Mono.just(Decision.allow());

        @Override
        public Mono<Decision> check(String group, String key) {
            calls.add(group + "|" + key);
            return next.get();
        }
    }

    private record Result(MockServerWebExchange exchange, ServerWebExchange forwarded) {

        boolean passed() {
            return forwarded != null;
        }

        int status() {
            return exchange.getResponse().getStatusCode().value();
        }

        String body() {
            return exchange.getResponse().getBodyAsString().block(Duration.ofSeconds(5));
        }

        Map<String, Object> json() {
            return Json.parseObject(body());
        }

        String code() {
            return (String) json().get("code");
        }

        String header(String name) {
            return exchange.getResponse().getHeaders().getFirst(name);
        }

        String forwardedHeader(String name) {
            return forwarded.getRequest().getHeaders().getFirst(name);
        }
    }

    private Result run(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            forwarded.set(ex);
            return ex.getResponse().setComplete();
        };
        filter.filter(exchange, chain).block(Duration.ofSeconds(5));
        return new Result(exchange, forwarded.get());
    }

    private MockServerHttpRequest.BaseBuilder<?> request(String method, String path) {
        return MockServerHttpRequest.method(HttpMethod.valueOf(method), path);
    }

    private Result byKey(String method, String path, String value) {
        return run(request(method, path).header("X-Api-Key", value).build());
    }

    private Result byToken(String method, String path, String token) {
        return run(request(method, path).header("Authorization", "Bearer " + token).build());
    }

    /** Токен продавца из кабинета: область seller.catalog, второй фактор, профиль продавца в claim seller_id. */
    private String sellerToken() {
        return tokens.token().subject(SELLER_USER).scopes("seller.catalog").roles("seller").amr("pwd", "otp").sellerId(SELLER).build();
    }

    private double rejected(String reason) {
        var counter = meters.find("dgm.gateway.rejected").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private void issued() {
        catalog.issue(SAMPLE, KEY_ID, SELLER, "approved");
    }

    // ---------------------------------------------------------------------------------------------------- TC-955: проверка ключа

    @Test
    @Tag("TC-955")
    void aKeyOfAnApprovedSellerPassesOnARouteWithBothLoginsAndTheServiceGetsOnlyTheIdentity() {
        issued();
        Result r = byKey("GET", STOCK, SAMPLE);
        assertTrue(r.passed(), "ключ действующий, профиль одобрен: запрос идёт сервису");
        assertEquals(SELLER, r.forwardedHeader("X-Seller-Id"));
        assertEquals("api-key", r.forwardedHeader("X-Auth-Kind"));
        assertNull(r.forwardedHeader("X-Api-Key"), "значение ключа сервис не получает");
        assertNull(r.forwardedHeader("Authorization"));
        assertEquals(List.of(SAMPLE_DIGEST), catalog.hashes, "каталогу уходит хеш, а не значение");
    }

    @Test
    @Tag("TC-955")
    void theHashSentToTheCatalogIsTheSha256OfTheValue() {
        assertEquals(SAMPLE_DIGEST, ApiKeyVerifier.hash(SAMPLE));
        assertEquals(64, ApiKeyVerifier.hash(OTHER_SAMPLE).length());
    }

    @Test
    @Tag("TC-955")
    void aKeyOnAKeyOnlyRouteWorksAndATokenOnItIsNotAccepted() {
        issued();
        assertTrue(byKey("POST", ANSWER, SAMPLE).passed());

        Result withToken = byToken("POST", ANSWER, sellerToken());
        assertFalse(withToken.passed());
        assertEquals(401, withToken.status(), "токен пользователя на маршруте только для ключа не принимается");
        assertEquals("unauthenticated", withToken.code());

        Result nothing = run(request("POST", ANSWER).build());
        assertEquals(401, nothing.status());
        assertEquals("unauthenticated", nothing.code());
        assertEquals(1, catalog.calls.get(), "проверялся только первый запрос");
    }

    @Test
    @Tag("TC-955")
    void aKeyOnARouteForTokensOnlyIsUnauthenticatedAndNeverChecked() {
        issued();
        for (String[] route : List.of(new String[] {"GET", KEYS_LIST}, new String[] {"POST", KEYS_LIST},
                new String[] {"GET", "/api/v1/seller/integration"})) {
            Result r = byKey(route[0], route[1], SAMPLE);
            assertFalse(r.passed(), route[1]);
            assertEquals(401, r.status(), route[1]);
        }
        assertEquals(0, catalog.calls.get(), "ключ там, где он не принимается, не проверяется: каталог не нагружается");
        assertTrue(cache.gets.isEmpty());
        assertTrue(limiter.calls.isEmpty());
    }

    @Test
    @Tag("TC-955")
    void aTokenAndAKeyTogetherAreRefusedAsAmbiguousBeforeAnyCheck() {
        issued();
        for (String[] route : List.of(new String[] {"GET", STOCK}, new String[] {"POST", ANSWER}, new String[] {"GET", KEYS_LIST})) {
            Result r = run(request(route[0], route[1]).header("Authorization", "Bearer " + sellerToken()).header("X-Api-Key", SAMPLE).build());
            assertFalse(r.passed(), route[1]);
            assertEquals(400, r.status(), route[1]);
            assertEquals("bad-request", r.code(), route[1]);
        }
        Result twoKeys = run(request("GET", STOCK).header("X-Api-Key", SAMPLE).header("X-Api-Key", OTHER_SAMPLE).build());
        assertEquals(400, twoKeys.status(), "два заголовка с ключом это тоже неоднозначная личность");
        assertEquals(0, catalog.calls.get());
        assertTrue(cache.gets.isEmpty());
        assertTrue(limiter.calls.isEmpty());
        assertEquals(4, rejected("ambiguous_identity"), 0.0);
    }

    @Test
    @Tag("TC-955")
    void valuesOfAnotherFormatAreUnauthenticatedWithoutTouchingTheCacheOrTheCatalog() {
        List<String> bad = new ArrayList<>(List.of("", " ", "dgm_", "dgm_short", SAMPLE.substring(0, SAMPLE.length() - 1), SAMPLE + "A",
                "DGM_" + SAMPLE.substring(4), "dgm-" + SAMPLE.substring(4), " " + SAMPLE, SAMPLE + " ", SAMPLE.replace('A', '+'),
                SAMPLE.substring(0, 40) + "==", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln", "Bearer " + SAMPLE));
        for (String value : bad) {
            Result r = run(request("GET", STOCK).header("X-Api-Key", value).build());
            assertFalse(r.passed(), "[" + value + "]");
            assertEquals(401, r.status(), "[" + value + "]");
            assertEquals("unauthenticated", r.code(), "[" + value + "]");
        }
        assertEquals(0, catalog.calls.get(), "правдоподобное значение чужого формата не доходит до каталога");
        assertTrue(cache.gets.isEmpty(), "и до Redis");
        assertTrue(limiter.calls.isEmpty());
    }

    @Test
    @Tag("TC-955")
    void anUnknownKeyAndARevokedKeyGetTheSameAnswer() {
        Result unknown = byKey("GET", STOCK, SAMPLE);
        issued();
        clock.advance(Duration.ofSeconds(10));        // память об отказе (5 секунд) истекла
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        catalog.revoke(SAMPLE);
        clock.advance(Duration.ofSeconds(31));        // память об успехе (30 секунд) истекла
        Result revoked = byKey("GET", STOCK, SAMPLE);

        assertEquals(401, unknown.status());
        assertEquals(401, revoked.status());
        for (String field : List.of("status", "code", "title", "detail")) {
            assertEquals(unknown.json().get(field), revoked.json().get(field), field + ": ответ на неизвестный и отозванный ключ один");
        }
        assertEquals("unauthenticated", revoked.code());
        assertNull(revoked.header("WWW-Authenticate"));
    }

    @Test
    @Tag("TC-955")
    void aKeyOfASellerWhoIsNotApprovedIsForbiddenAndTheStatusDecidesAtTheGateway() {
        for (String status : List.of("blocked", "draft", "submitted", "rejected", "on_review")) {
            clock.advance(Duration.ofSeconds(31));
            catalog.issue(SAMPLE, KEY_ID, SELLER, status);
            Result r = byKey("GET", STOCK, SAMPLE);
            assertFalse(r.passed(), status);
            assertEquals(403, r.status(), status);
            assertEquals("forbidden", r.code(), status);
        }
        assertEquals(5, rejected("seller_not_approved"), 0.0);
        assertTrue(limiter.calls.isEmpty(), "отклонённый запрос не тратит лимит");
    }

    @Test
    @Tag("TC-955")
    void whenTheCatalogIsDownAnUnknownKeyGets503WithRetryAfter() {
        catalog.down = true;
        issued();
        Result r = byKey("GET", STOCK, SAMPLE);
        assertFalse(r.passed());
        assertEquals(503, r.status());
        assertEquals("dependency-unavailable", r.code());
        assertEquals("5", r.header("Retry-After"));
        assertEquals(1, rejected("api_key_unavailable"), 0.0);
        assertEquals(0, cache.size(), "итог «не удалось проверить» в память не попадает");
    }

    @Test
    @Tag("TC-955")
    void aProblemWithTheCatalogAnswerIsAnOutageNotARefusalToTheKey() {
        // Каталог признал ключ действительным, но не назвал продавца: это сбой проверки, а не отказ держателю ключа
        catalog.override = () -> Mono.just(new ApiKeyVerifier.CatalogAnswer(true, KEY_ID, null, "approved"));
        Result r = byKey("GET", STOCK, SAMPLE);
        assertEquals(503, r.status());
        assertEquals(0, cache.size());
    }

    // ---------------------------------------------------------------------------------------------------- TC-956: память о ключах

    @Test
    @Tag("TC-956")
    void aThousandRequestsWithOneKeyMakeOneCatalogCall() {
        issued();
        for (int i = 0; i < 1000; i++) {
            assertTrue(byKey("GET", STOCK, SAMPLE).passed(), "запрос " + i);
        }
        assertEquals(1, catalog.calls.get());
        assertEquals(1000, cache.gets.size());
        assertEquals(Duration.ofSeconds(30), cache.ttls.get(SAMPLE_DIGEST), "успех помнится 30 секунд");
    }

    @Test
    @Tag("TC-956")
    void aRevokedKeyWorksUntilTheMemoryExpiresAndNotLonger() {
        issued();
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        catalog.revoke(SAMPLE);

        clock.advance(Duration.ofSeconds(29));
        assertTrue(byKey("GET", STOCK, SAMPLE).passed(), "шлюз не подписан на отзыв: до конца срока памяти ключ ещё работает");
        assertEquals(1, catalog.calls.get());

        clock.advance(Duration.ofSeconds(1));
        Result refused = byKey("GET", STOCK, SAMPLE);
        assertEquals(401, refused.status(), "срок памяти прошёл: шлюз спросил каталог заново и получил отказ");
        assertEquals(2, catalog.calls.get());
    }

    @Test
    @Tag("TC-956")
    void aRefusalIsRememberedForFiveSecondsOnly() {
        assertEquals(401, byKey("GET", STOCK, SAMPLE).status());
        assertEquals(Duration.ofSeconds(5), cache.ttls.get(SAMPLE_DIGEST), "отказ помнится короче успеха");
        issued();

        clock.advance(Duration.ofSeconds(4));
        assertEquals(401, byKey("GET", STOCK, SAMPLE).status(), "в пределах пяти секунд отказ из памяти, каталог не нагружается");
        assertEquals(1, catalog.calls.get());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        assertEquals(2, catalog.calls.get());
    }

    @Test
    @Tag("TC-956")
    void theStatusOfTheSellerIsPartOfWhatIsRemembered() {
        catalog.issue(SAMPLE, KEY_ID, SELLER, "blocked");
        assertEquals(403, byKey("GET", STOCK, SAMPLE).status());
        catalog.issue(SAMPLE, KEY_ID, SELLER, "approved");

        clock.advance(Duration.ofSeconds(4));
        assertEquals(403, byKey("GET", STOCK, SAMPLE).status(), "отказ по статусу тоже помнится пять секунд");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(byKey("GET", STOCK, SAMPLE).passed(), "профиль одобрен: шлюз узнал об этом не позже срока памяти");
    }

    @Test
    @Tag("TC-956")
    void onTheStandWithAShortMemoryAnEnabledKeyAndARevokedOneChangeWithinThatTerm() {
        properties.setApiKeyCacheSeconds(2);
        issued();
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        assertEquals(Duration.ofSeconds(2), cache.ttls.get(SAMPLE_DIGEST));
        catalog.revoke(SAMPLE);
        clock.advance(Duration.ofSeconds(2));
        assertEquals(401, byKey("GET", STOCK, SAMPLE).status());
        assertEquals(Duration.ofSeconds(2), cache.ttls.get(SAMPLE_DIGEST), "отказ не помнится дольше успеха");
    }

    @Test
    @Tag("TC-956")
    void whileTheCatalogIsDownKeysFromTheMemoryKeepWorkingUntilTheTermEnds() {
        issued();
        catalog.issue(OTHER_SAMPLE, OTHER_KEY_ID, OTHER_SELLER, "approved");
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        catalog.down = true;

        assertTrue(byKey("GET", STOCK, SAMPLE).passed(), "доступность важнее точности: ключ из памяти проходит");
        assertEquals(503, byKey("GET", STOCK, OTHER_SAMPLE).status(), "новый ключ проверить нельзя");

        clock.advance(Duration.ofSeconds(30));
        assertEquals(503, byKey("GET", STOCK, SAMPLE).status(), "срок памяти прошёл, каталога нет");
        catalog.down = false;
        assertTrue(byKey("GET", STOCK, SAMPLE).passed(), "каталог вернулся: проверка снова работает");
    }

    @Test
    @Tag("TC-956")
    void anUnreachableMemoryDoesNotStopTheCheck() {
        issued();
        cache.failGet = true;
        cache.failPut = true;
        assertTrue(byKey("GET", STOCK, SAMPLE).passed(), "Redis не отвечает: решает каталог");
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        assertEquals(2, catalog.calls.get(), "память не работает, поэтому каждый запрос спрашивает каталог");
        assertEquals(2, meters.find("dgm.gateway.apikey.cache.failures").tag("op", "get").counter().count(), 0.0);
        assertEquals(2, meters.find("dgm.gateway.apikey.cache.failures").tag("op", "put").counter().count(), 0.0);
    }

    // ---------------------------------------------------------------------------------------------------- TC-957: лимиты

    @Test
    @Tag("TC-957")
    void aCallByKeyIsCountedPerKeyInTheKeyGroupAndByTokenPerUserInTheCabinetGroup() {
        issued();
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        assertTrue(byToken("GET", STOCK, sellerToken()).passed());
        assertEquals(List.of("seller.api|k:" + KEY_ID, "seller|u:" + SELLER_USER), limiter.calls);
    }

    @Test
    @Tag("TC-957")
    void aKeyOnlyRouteIsCountedPerKeyToo() {
        issued();
        assertTrue(byKey("POST", ANSWER, SAMPLE).passed());
        assertEquals(List.of("seller.api|k:" + KEY_ID), limiter.calls);
    }

    @Test
    @Tag("TC-957")
    void uploadsAreCountedPerSellerSoThatTheCabinetAndTheApiShareOneLimit() {
        issued();
        assertTrue(byKey("POST", UPLOAD, SAMPLE).passed());
        assertTrue(byToken("POST", UPLOAD, sellerToken()).passed());
        String expected = "seller-upload|s:" + SELLER;
        assertEquals(List.of(expected, expected), limiter.calls, "и ключ, и токен считаются на один и тот же профиль продавца");

        // У токена без claim seller_id (профиля ещё нет) счёт идёт по пользователю: общего с ключом у него быть не может
        limiter.calls.clear();
        String withoutProfile = tokens.token().subject(SELLER_USER).scopes("seller.catalog").roles("seller").amr("pwd", "otp").build();
        assertTrue(byToken("POST", UPLOAD, withoutProfile).passed());
        assertEquals(List.of("seller-upload|u:" + SELLER_USER), limiter.calls);
    }

    @Test
    @Tag("TC-957")
    void anExhaustedUploadLimitGives429WithRetryAfterForKeyAndToken() {
        issued();
        limiter.next = () -> Mono.just(RequestLimiter.Decision.deny(600));
        for (Result r : List.of(byKey("POST", UPLOAD, SAMPLE), byToken("POST", UPLOAD, sellerToken()))) {
            assertFalse(r.passed());
            assertEquals(429, r.status());
            assertEquals("rate-limited", r.code());
            assertEquals("600", r.header("Retry-After"));
        }
        Result byKeyGroup = byKey("GET", STOCK, SAMPLE);
        assertEquals(429, byKeyGroup.status());
        assertEquals(3, rejected("rate_limited"), 0.0);
    }

    @Test
    @Tag("TC-957")
    void whenRedisDoesNotAnswerKeyCallsPassAndTheFailureIsCounted() {
        issued();
        limiter.next = () -> Mono.error(new IllegalStateException("Redis не отвечает"));
        assertTrue(byKey("POST", UPLOAD, SAMPLE).passed());
        assertTrue(byKey("GET", STOCK, SAMPLE).passed());
        assertEquals(1, meters.find("dgm.gateway.ratelimit.failopen").tag("group", "seller-upload").counter().count(), 0.0);
        assertEquals(1, meters.find("dgm.gateway.ratelimit.failopen").tag("group", "seller.api").counter().count(), 0.0);
    }

    @Test
    @Tag("TC-957")
    void everyKeyGroupOfTheTableHasASettingInTheLimitMap() {
        for (var rule : routes.rules()) {
            RouteTable.Target target = routes.target(rule.operationId()).orElseThrow();
            assertTrue(properties.limit(target.limit()) != null, rule.operationId() + ": " + target.limit());
            assertTrue(properties.limit(target.keyLimit()) != null, rule.operationId() + ": " + target.keyLimit());
        }
    }

    // ---------------------------------------------------------------------------------------------------- TC-958: заголовки личности

    @Test
    @Tag("TC-958")
    void identityHeadersFromTheOutsideAreRemovedOnEveryKindOfRoute() {
        // На открытом маршруте ключа в запросе быть может (его никто не проверяет, но и сервису он не нужен); на маршрутах с токеном
        // ключ рядом с токеном это отказ 400, поэтому там подделываются только заголовки личности
        List<Result> results = List.of(
                run(request("GET", "/api/v1/products").header("X-Seller-Id", OTHER_SELLER).header("X-Auth-Kind", "api-key")
                        .header("X-Api-Key", THIRD_SAMPLE).build()),
                run(request("GET", KEYS_LIST).header("Authorization", "Bearer " + sellerToken()).header("X-Seller-Id", OTHER_SELLER)
                        .header("X-Auth-Kind", "api-key").build()),
                run(request("GET", STOCK).header("Authorization", "Bearer " + sellerToken()).header("X-Seller-Id", OTHER_SELLER)
                        .header("X-Auth-Kind", "api-key").build()));
        for (Result r : results) {
            assertTrue(r.passed());
            assertNull(r.forwardedHeader("X-Seller-Id"), "личность по ключу может дать только шлюз");
            assertNull(r.forwardedHeader("X-Auth-Kind"));
            assertNull(r.forwardedHeader("X-Api-Key"));
        }
    }

    @Test
    @Tag("TC-958")
    void aForgedIdentityNextToAValidKeyChangesNothingTheGatewayStampsTheVerifiedSeller() {
        issued();
        Result r = run(request("GET", STOCK).header("X-Api-Key", SAMPLE).header("X-Seller-Id", OTHER_SELLER).header("X-Auth-Kind", "other")
                .build());
        assertTrue(r.passed());
        assertEquals(SELLER, r.forwardedHeader("X-Seller-Id"));
        assertEquals("api-key", r.forwardedHeader("X-Auth-Kind"));
    }

    @Test
    @Tag("TC-958")
    void aTokenCallGetsNoIdentityHeadersAndTheTokenIsForwardedUnchanged() {
        String token = sellerToken();
        Result r = byToken("GET", STOCK, token);
        assertTrue(r.passed());
        assertEquals("Bearer " + token, r.forwardedHeader("Authorization"));
        assertNull(r.forwardedHeader("X-Seller-Id"));
        assertNull(r.forwardedHeader("X-Auth-Kind"));
    }

    @Test
    @Tag("TC-958")
    void theHeaderNamesAreTheOnesTheServicesRead() {
        assertEquals("X-Seller-Id", EntryFilter.SELLER_ID_HEADER);
        assertEquals("X-Auth-Kind", EntryFilter.AUTH_KIND_HEADER);
        assertEquals("api-key", EntryFilter.AUTH_KIND_API_KEY);
        assertEquals("X-Api-Key", EntryFilter.API_KEY_HEADER);
    }

    // ---------------------------------------------------------------------------------------------------- значение ключа нигде не светится

    @Test
    @Tag("TC-955")
    void theValueOfTheKeyIsNowhereInAnswersMetersOrTheMemoryOfTheGateway() {
        issued();
        List<String> seen = new ArrayList<>();
        for (Result r : List.of(byKey("GET", STOCK, SAMPLE), byKey("GET", STOCK, OTHER_SAMPLE), byKey("GET", KEYS_LIST, SAMPLE),
                run(request("GET", STOCK).header("X-Api-Key", SAMPLE).header("Authorization", "Bearer x").build()))) {
            if (!r.passed()) {
                seen.add(r.body());
            }
            seen.add(r.exchange().getResponse().getHeaders().toString());
        }
        catalog.down = true;
        seen.add(byKey("GET", STOCK, THIRD_SAMPLE).body());
        seen.add(cache.dump());
        seen.add(String.join(",", catalog.hashes));
        seen.addAll(limiter.calls);
        for (Meter meter : meters.getMeters()) {
            seen.add(meter.getId().toString());
        }
        for (String text : seen) {
            for (String value : List.of(SAMPLE, OTHER_SAMPLE, THIRD_SAMPLE)) {
                assertFalse(text.contains(value), "значение ключа попало в ответ, метрику, память или обращение: " + text);
                assertFalse(text.contains(value.substring(4)), "тело значения ключа попало в ответ, метрику, память или обращение");
            }
        }
        assertTrue(cache.dump().isEmpty() || !cache.dump().contains("dgm_"));
    }
}
