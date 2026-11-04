package dgm.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * Память о ключах в оперативной памяти для тестов шлюза. Живёт по часам теста, как Redis по срокам записей: запись старше своего срока
 * пропадает. Запоминает обращения и сроки, умеет «не отвечать» (Redis недоступен).
 */
final class FakeKeyCache implements ApiKeyCache {

    private record Entry(ApiKeyVerifier.Verdict verdict, Instant expires) {
    }

    private final Clock clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    final List<String> gets = new CopyOnWriteArrayList<>();
    final Map<String, Duration> ttls = new ConcurrentHashMap<>();
    volatile boolean failGet;
    volatile boolean failPut;

    FakeKeyCache(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<ApiKeyVerifier.Verdict> get(String hash) {
        gets.add(hash);
        if (failGet) {
            return Mono.error(new IllegalStateException("Redis не отвечает"));
        }
        Entry entry = entries.get(hash);
        return entry == null || !clock.instant().isBefore(entry.expires()) ? Mono.empty() : Mono.just(entry.verdict());
    }

    @Override
    public Mono<Void> put(String hash, ApiKeyVerifier.Verdict verdict, Duration ttl) {
        if (failPut) {
            return Mono.error(new IllegalStateException("Redis не отвечает"));
        }
        entries.put(hash, new Entry(verdict, clock.instant().plus(ttl)));
        ttls.put(hash, ttl);
        return Mono.empty();
    }

    /** Очищает память и обращения (для памяти, общей на все тесты класса). */
    void clear() {
        entries.clear();
        gets.clear();
        ttls.clear();
        failGet = false;
        failPut = false;
    }

    int size() {
        return entries.size();
    }

    /** Всё, что лежит в памяти: ключи записей и итоги, для проверки, что значения ключа среди них нет. */
    String dump() {
        return entries.toString();
    }
}
