package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.time.ManualClock;
import dgm.kit.trace.TraceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Проверка ключа без фильтра: один вызов каталога на значение, срок ожидания, разбор ответа каталога, формат (ADR-027, п. 3). */
class ApiKeyVerifierTest {

    private static final String SAMPLE = "dgm_AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd";
    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";
    private static final String KEY_ID = "0199e0a0-0000-7000-8000-000000000901";

    private final ManualClock clock = new ManualClock(Instant.parse("2026-11-04T10:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GatewayProperties properties = EntryFilterTest.properties();
    private final FakeKeyCache cache = new FakeKeyCache(clock);
    private final FakeCatalog catalog = new FakeCatalog();
    private final ApiKeyVerifier verifier = new ApiKeyVerifier(cache, catalog, properties, meters, clock);
    private final TraceContext trace = TraceContext.fresh();

    private ApiKeyVerifier.Verdict verify(String value) {
        return verifier.verify(value, trace).block(Duration.ofSeconds(5));
    }

    @Test
    @Tag("TC-955")
    void theFormatIsThePrefixAndFortyCharactersOfBase64Url() {
        assertTrue(ApiKeyVerifier.wellFormed(SAMPLE));
        assertTrue(ApiKeyVerifier.wellFormed("dgm_" + "A".repeat(40)));
        assertTrue(ApiKeyVerifier.wellFormed("dgm_" + "_-".repeat(20)));
        assertFalse(ApiKeyVerifier.wellFormed(null));
        assertFalse(ApiKeyVerifier.wellFormed(""));
        assertFalse(ApiKeyVerifier.wellFormed("dgm_" + "A".repeat(39)));
        assertFalse(ApiKeyVerifier.wellFormed("dgm_" + "A".repeat(41)));
        assertFalse(ApiKeyVerifier.wellFormed("dgm_" + "A".repeat(39) + "+"));
        assertFalse(ApiKeyVerifier.wellFormed("dgm_" + "A".repeat(39) + "\n"));
        assertFalse(ApiKeyVerifier.wellFormed("xgm_" + "A".repeat(40)));
    }

    @Test
    @Tag("TC-955")
    void aCatalogAnswerBecomesAVerdictAndOnlyAnApprovedSellerIsAccepted() {
        assertEquals(new ApiKeyVerifier.Verdict.Accepted(KEY_ID, SELLER),
                ApiKeyVerifier.verdictOf(new ApiKeyVerifier.CatalogAnswer(true, KEY_ID, SELLER, "approved")));
        assertEquals(new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.INVALID), ApiKeyVerifier.verdictOf(FakeCatalog.UNKNOWN));
        for (String status : List.of("blocked", "draft", "submitted", "rejected", "on_review", "APPROVED", "")) {
            assertEquals(new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.SELLER_NOT_APPROVED),
                    ApiKeyVerifier.verdictOf(new ApiKeyVerifier.CatalogAnswer(true, KEY_ID, SELLER, status)), status);
        }
        for (ApiKeyVerifier.CatalogAnswer broken : List.of(new ApiKeyVerifier.CatalogAnswer(true, null, SELLER, "approved"),
                new ApiKeyVerifier.CatalogAnswer(true, KEY_ID, null, "approved"), new ApiKeyVerifier.CatalogAnswer(true, KEY_ID, SELLER, null))) {
            assertThrows(IllegalStateException.class, () -> ApiKeyVerifier.verdictOf(broken), broken.toString());
        }
    }

    @Test
    @Tag("TC-956")
    void severalRequestsForTheSameNewValueWaitForOneCatalogAnswer() throws Exception {
        Sinks.One<ApiKeyVerifier.CatalogAnswer> answer = Sinks.one();
        catalog.override = answer::asMono;
        List<CompletableFuture<ApiKeyVerifier.Verdict>> all = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            all.add(verifier.verify(SAMPLE, trace).toFuture());
        }
        assertEquals(1, catalog.calls.get(), "пятьдесят запросов с одним значением дают один вызов каталога");
        assertTrue(all.stream().noneMatch(CompletableFuture::isDone), "ответа каталога ещё нет");

        answer.tryEmitValue(new ApiKeyVerifier.CatalogAnswer(true, KEY_ID, SELLER, "approved"));
        for (CompletableFuture<ApiKeyVerifier.Verdict> future : all) {
            assertEquals(new ApiKeyVerifier.Verdict.Accepted(KEY_ID, SELLER), future.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, catalog.calls.get());
        assertEquals(new ApiKeyVerifier.Verdict.Accepted(KEY_ID, SELLER), verify(SAMPLE));
        assertEquals(1, catalog.calls.get(), "следующий запрос читает память");
    }

    @Test
    @Tag("TC-956")
    void aSharedAnswerIsNotKeptAfterItIsDeliveredSoALaterCheckAsksAgain() {
        cache.failPut = true;
        catalog.issue(SAMPLE, KEY_ID, SELLER, "approved");
        verify(SAMPLE);
        verify(SAMPLE);
        assertEquals(2, catalog.calls.get(), "запись в память не удалась, общий ответ не должен жить дольше своего запроса");
    }

    @Test
    @Tag("TC-955")
    void aCatalogThatNeverAnswersEndsAsUnavailableAfterTheTimeoutAndIsNotRemembered() {
        properties.setApiKeyCheckTimeout(Duration.ofMillis(100));
        catalog.override = Mono::never;
        assertInstanceOf(ApiKeyVerifier.Verdict.Unavailable.class, verify(SAMPLE));
        assertEquals(0, cache.size());
        assertEquals(1, meters.find("dgm.gateway.apikey.checks").tag("source", "catalog").tag("result", "unavailable").counter().count(), 0.0);
    }

    @Test
    @Tag("TC-955")
    void everyCheckIsCountedBySourceAndResult() {
        catalog.issue(SAMPLE, KEY_ID, SELLER, "approved");
        verify("dgm_short");
        verify(SAMPLE);
        verify(SAMPLE);
        assertEquals(1, meters.find("dgm.gateway.apikey.checks").tag("source", "local").tag("result", "malformed").counter().count(), 0.0);
        assertEquals(1, meters.find("dgm.gateway.apikey.checks").tag("source", "catalog").tag("result", "accepted").counter().count(), 0.0);
        assertEquals(1, meters.find("dgm.gateway.apikey.checks").tag("source", "cache").tag("result", "accepted").counter().count(), 0.0);
    }

    @Test
    @Tag("TC-956")
    void theTermsOfTheMemoryFollowTheSettingsAndNeverGoBelowOneSecond() {
        assertEquals(Duration.ofSeconds(30), properties.apiKeyTtl());
        assertEquals(Duration.ofSeconds(5), properties.apiKeyNegativeTtl());
        properties.setApiKeyCacheSeconds(2);
        assertEquals(Duration.ofSeconds(2), properties.apiKeyTtl());
        assertEquals(Duration.ofSeconds(2), properties.apiKeyNegativeTtl(), "отказ не помнится дольше успеха");
        properties.setApiKeyCacheSeconds(0);
        assertEquals(Duration.ofSeconds(1), properties.apiKeyTtl());
        assertEquals(Duration.ofSeconds(1), properties.apiKeyNegativeTtl());
    }
}
