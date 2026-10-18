package dgm.kit.boot;

import dgm.kit.client.PeerClient;
import dgm.kit.retry.RetryPolicy;
import dgm.kit.retry.Sleeper;
import dgm.kit.tls.TlsMaterial;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import javax.net.ssl.SSLContext;

/**
 * Клиенты соседей сервиса по имени (Р-140). Соседи описаны в {@code dgm.peers.<имя>.*} ({@link KitProperties.Peer}); клиент создаётся
 * при первом обращении и дальше переиспользуется, поэтому предохранитель у соседа один на весь сервис, а не на запрос. Для адресов https
 * берётся клиентский сертификат сервиса (взаимная проверка), для http (тесты) TLS нет.
 */
public final class PeerClients {

    private final Map<String, KitProperties.Peer> peers;
    private final TlsMaterial tls;
    private final Clock clock;
    private final Sleeper sleeper;
    private final MeterRegistry metrics;
    private final Map<String, PeerClient> clients = new ConcurrentHashMap<>();
    private volatile SSLContext mutual;

    public PeerClients(KitProperties properties, TlsMaterial tls, Clock clock, Sleeper sleeper, MeterRegistry metrics) {
        this.peers = Map.copyOf(properties.peers());
        this.tls = tls;
        this.clock = clock;
        this.sleeper = sleeper;
        this.metrics = metrics;
    }

    /**
     * Клиент соседа.
     *
     * @throws IllegalArgumentException если сосед не описан в {@code dgm.peers} или не задан его адрес
     */
    public PeerClient get(String name) {
        return clients.computeIfAbsent(name, this::create);
    }

    private PeerClient create(String name) {
        KitProperties.Peer peer = peers.get(name);
        if (peer == null || peer.baseUrl() == null || peer.baseUrl().isBlank()) {
            throw new IllegalArgumentException("Сосед «" + name + "» не настроен: задайте dgm.peers." + name + ".base-url");
        }
        URI base = URI.create(peer.baseUrl().trim());
        RetryPolicy retry = RetryPolicy.parse(peer.retry(), peer.jitter(), RandomGenerator.getDefault());
        Duration total = peer.totalDeadline() != null ? peer.totalDeadline()
                : peer.attemptTimeout().multipliedBy(retry.maxAttempts()).plus(retry.totalMaxPause());
        PeerClient.Settings settings = new PeerClient.Settings(name, base, peer.attemptTimeout(), total, retry, peer.breakerWindow(),
                peer.breakerRatio(), peer.breakerOpenFor());
        SSLContext context = "https".equalsIgnoreCase(base.getScheme()) ? mutualContext() : null;
        return new PeerClient(settings, context, clock, sleeper, metrics);
    }

    private SSLContext mutualContext() {
        SSLContext current = mutual;
        if (current == null) {
            synchronized (this) {
                if (mutual == null) {
                    mutual = tls.mutualContext();
                }
                current = mutual;
            }
        }
        return current;
    }
}
