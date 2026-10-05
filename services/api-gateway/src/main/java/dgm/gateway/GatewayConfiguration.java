package dgm.gateway;

import dgm.kit.probe.ReadinessProbe;
import dgm.kit.security.JwtDecoders;
import dgm.kit.security.JwtSettings;
import dgm.kit.time.Clocks;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import java.io.IOException;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Сборка шлюза (ADR-021). Профиль {@code notls} нужен только тестам: без него соединения со всеми сервисами идут по TLS с клиентским
 * сертификатом шлюза, ключи Keycloak берутся по TLS с нашим центром, а ограничитель частоты работает на Redis.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
class GatewayConfiguration {

    /** Имя пакета сертификатов (spring.ssl.bundle.pem.dgm): сертификат и ключ шлюза, центр сертификации стенда. */
    static final String BUNDLE = "dgm";

    @Bean
    RouteTable routeTable(GatewayProperties properties) {
        return RouteTable.fromClasspath("dgm/gateway-routes.json", properties.getUpstreams().keySet());
    }

    /**
     * Один маршрут Spring Cloud Gateway на каждый сервис-получатель. Выбор делает не маршрутизатор Spring, а {@link EntryFilter}: он
     * кладёт в запрос выбранную цель, предикат маршрута только сверяет имя сервиса. Заголовок Host не меняется там, где подпись
     * запроса его покрывает (хранилище S3) или адрес для ссылок строится по нему (Keycloak).
     */
    @Bean
    RouteLocator dgmRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        properties.getUpstreams().forEach((service, uri) -> {
            boolean keepHost = service.equals("keycloak") || service.equals("object-storage");
            routes.route(service, r -> r.predicate(exchange -> {
                RouteTable.Target target = exchange.getAttribute(EntryFilter.TARGET);
                return target != null && target.service().equals(service);
            }).filters(f -> keepHost ? f.preserveHostHeader() : f).uri(uri));
        });
        return routes.build();
    }

    @Bean
    TokenVerifier tokenVerifier(JwtDecoder decoder) {
        return new TokenVerifier(decoder);
    }

    @Bean
    EntryFilter entryFilter(RouteTable routes, TokenVerifier tokens, RequestLimiter limiter, GatewayProperties properties,
            ObjectProvider<MeterRegistry> meters, @Value("${management.server.port:-1}") int managementPort) {
        return new EntryFilter(routes, tokens, limiter, properties, meters.getIfAvailable(SimpleMeterRegistry::new), Clocks.system(),
                managementPort);
    }

    @Bean
    ProblemErrorHandler problemErrorHandler(ObjectProvider<MeterRegistry> meters) {
        return new ProblemErrorHandler(meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** Проверка готовности для Docker на 127.0.0.1: приложение приняло трафик. Redis не нужен: без него шлюз работает (отказ открытым). */
    @Bean(initMethod = "start", destroyMethod = "close")
    ReadinessProbe readinessProbe(@Value("${dgm.probe.port:8081}") int port, ApplicationAvailability availability) throws IOException {
        return new ReadinessProbe(port, List.of(() -> availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC));
    }

    /** Ограничитель частоты на Redis; в тестах вместо него подставляется свой {@link RequestLimiter}. */
    @Bean
    @Profile("!notls")
    RequestLimiter redisRequestLimiter(RedisRateLimiter delegate, GatewayProperties properties) {
        return new RedisRequestLimiter(delegate, properties.getLimits());
    }

    /** Проверка подписи токенов по ключам Keycloak: ключи грузятся лениво по TLS с нашим центром, поэтому шлюз стартует без Keycloak. */
    @Bean
    @Profile("!notls")
    JwtDecoder jwtDecoder(SslBundles bundles, @Value("${dgm.security.jwt.issuer}") String issuer,
            @Value("${dgm.security.jwt.jwks-uri}") String jwksUri) {
        return JwtDecoders.forJwkSet(new JwtSettings(issuer, jwksUri), bundles.getBundle(BUNDLE).createSslContext());
    }

    /**
     * Соединения шлюза с сервисами по mTLS: клиентский сертификат шлюза и наш центр сертификации из пакета {@code dgm}, имя сервера в
     * сертификате сверяется с адресом (контейнер {@code catalog-service} предъявляет сертификат с именем {@code catalog-service}).
     */
    @Bean
    @Profile("!notls")
    HttpClientCustomizer upstreamTls(SslBundles bundles, GatewayProperties properties) {
        SslContext context = new JdkSslContext(bundles.getBundle(BUNDLE).createSslContext(), true, ClientAuth.NONE);
        return client -> client
                .secure(spec -> spec.sslContext(context).handlerConfigurator(GatewayConfiguration::verifyHostname))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.getConnectTimeout().toMillis())
                .responseTimeout(properties.getResponseTimeout());
    }

    private static void verifyHostname(SslHandler handler) {
        SSLEngine engine = handler.engine();
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(parameters);
    }
}
