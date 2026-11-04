package dgm.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Запись о ключе в Redis: формат, разбор и устойчивость к мусору в записи (ADR-027, п. 3). */
class RedisApiKeyCacheTest {

    private static final String SELLER = "0199e0a0-0000-7000-8000-000000000101";
    private static final String KEY_ID = "0199e0a0-0000-7000-8000-000000000901";

    @Test
    @Tag("TC-956")
    void everyStoredVerdictReadsBackAsItself() {
        for (ApiKeyVerifier.Verdict verdict : Arrays.<ApiKeyVerifier.Verdict>asList(new ApiKeyVerifier.Verdict.Accepted(KEY_ID, SELLER),
                new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.INVALID),
                new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.SELLER_NOT_APPROVED))) {
            assertEquals(verdict, RedisApiKeyCache.decode(RedisApiKeyCache.encode(verdict)), verdict.toString());
        }
    }

    @Test
    @Tag("TC-956")
    void theRecordHoldsTheIdentifiersButNeverTheValueOfTheKey() {
        assertEquals("ok|" + KEY_ID + "|" + SELLER, RedisApiKeyCache.encode(new ApiKeyVerifier.Verdict.Accepted(KEY_ID, SELLER)));
        assertEquals("no|invalid", RedisApiKeyCache.encode(new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.INVALID)));
        assertEquals("no|seller", RedisApiKeyCache.encode(new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.SELLER_NOT_APPROVED)));
    }

    @Test
    @Tag("TC-956")
    void aCheckThatFailedIsNotStored() {
        assertNull(RedisApiKeyCache.encode(new ApiKeyVerifier.Verdict.Unavailable()));
    }

    @Test
    @Tag("TC-956")
    void aRecordThatDoesNotParseIsAMissSoTheCatalogDecides() {
        for (String garbage : Arrays.asList(null, "", "ok", "ok|", "ok|a", "ok||b", "ok|a|", "ok|a|b|c", "no", "no|", "no|other", "yes|a|b",
                "OK|a|b", " no|invalid")) {
            assertNull(RedisApiKeyCache.decode(garbage), "[" + garbage + "]");
        }
    }

    @Test
    @Tag("TC-956")
    void theRecordsLiveUnderThePrefixThatTheAclOfTheGatewayAllows() {
        // infra/redis/entrypoint.sh: пользователю gateway разрешены только request_rate_limiter.* и apikey:*
        assertEquals("apikey:", RedisApiKeyCache.PREFIX);
    }
}
