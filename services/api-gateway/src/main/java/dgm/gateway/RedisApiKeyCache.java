package dgm.gateway;

import java.time.Duration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

/**
 * Память о ключах в Redis: запись {@code apikey:<SHA-256>} (так разрешает ACL пользователя {@code gateway}, infra/redis) со сроком жизни.
 * Формат записи: {@code ok|<идентификатор ключа>|<идентификатор продавца>}, {@code no|invalid} (неизвестный или отозванный) или
 * {@code no|seller} (продавец не одобрен). Запись, которую разобрать нельзя, считается промахом: проверка идёт к каталогу.
 */
public final class RedisApiKeyCache implements ApiKeyCache {

    static final String PREFIX = "apikey:";
    private static final String OK = "ok|";
    private static final String INVALID = "no|invalid";
    private static final String SELLER = "no|seller";

    private final ReactiveStringRedisTemplate redis;

    public RedisApiKeyCache(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<ApiKeyVerifier.Verdict> get(String hash) {
        return redis.opsForValue().get(PREFIX + hash).flatMap(text -> {
            ApiKeyVerifier.Verdict verdict = decode(text);
            return verdict == null ? Mono.empty() : Mono.just(verdict);
        });
    }

    @Override
    public Mono<Void> put(String hash, ApiKeyVerifier.Verdict verdict, Duration ttl) {
        String text = encode(verdict);
        if (text == null) {
            return Mono.empty();
        }
        return redis.opsForValue().set(PREFIX + hash, text, ttl).then();
    }

    /** Итог в запись; {@code null} для итога, который не хранится. */
    static String encode(ApiKeyVerifier.Verdict verdict) {
        return switch (verdict) {
            case ApiKeyVerifier.Verdict.Accepted accepted -> OK + accepted.apiKeyId() + "|" + accepted.sellerId();
            case ApiKeyVerifier.Verdict.Rejected rejected ->
                    rejected.reason() == ApiKeyVerifier.Reason.INVALID ? INVALID : SELLER;
            case ApiKeyVerifier.Verdict.Unavailable unavailable -> null;
        };
    }

    /** Запись в итог; {@code null}, если запись не разбирается. */
    static ApiKeyVerifier.Verdict decode(String text) {
        if (text == null) {
            return null;
        }
        if (INVALID.equals(text)) {
            return new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.INVALID);
        }
        if (SELLER.equals(text)) {
            return new ApiKeyVerifier.Verdict.Rejected(ApiKeyVerifier.Reason.SELLER_NOT_APPROVED);
        }
        if (text.startsWith(OK)) {
            String[] parts = text.substring(OK.length()).split("\\|", -1);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                return new ApiKeyVerifier.Verdict.Accepted(parts[0], parts[1]);
            }
        }
        return null;
    }
}
