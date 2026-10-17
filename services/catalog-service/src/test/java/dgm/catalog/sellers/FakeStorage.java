package dgm.catalog.sellers;

import dgm.kit.problem.ProblemException;
import dgm.kit.storage.ObjectStorage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Хранилище объектов в памяти для модульных тестов; настоящее хранилище проверяют интеграционные тесты. */
public final class FakeStorage implements ObjectStorage {

    /** Хранимый объект. */
    public record Stored(byte[] content, String contentType) {
    }

    private final Clock clock;
    private final Map<String, Stored> objects = new LinkedHashMap<>();
    /** Запись падает так же, как при недоступном хранилище: 503 dependency-unavailable. */
    public boolean failPut;
    /** Удаление падает исключением, которое сервис не должен пропускать в ответ. */
    public boolean failDelete;
    public Duration lastTtl;

    public FakeStorage(Clock clock) {
        this.clock = clock;
    }

    public Map<String, Stored> objects() {
        return objects;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (failPut) {
            throw ProblemException.dependencyUnavailable("Хранилище документов временно недоступно, повторите позже.", 5);
        }
        objects.put(key, new Stored(content.clone(), contentType));
    }

    @Override
    public void delete(String key) {
        if (failDelete) {
            throw new IllegalStateException("хранилище не отвечает");
        }
        objects.remove(key);
    }

    @Override
    public PresignedLink presignGet(String key, Duration ttl) {
        lastTtl = ttl;
        return new PresignedLink(URI.create("https://localhost:8443/files/seller-documents/" + key + "?X-Amz-Expires=" + ttl.toSeconds()),
                clock.instant().plus(ttl));
    }
}
