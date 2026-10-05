package dgm.kit.boot;

import dgm.kit.problem.ProblemAdvice;
import dgm.kit.route.RoutePolicy;
import dgm.kit.security.CallerFilter;
import dgm.kit.security.JwtDecoders;
import dgm.kit.security.JwtFilter;
import dgm.kit.security.JwtSettings;
import dgm.kit.tls.TlsMaterial;
import dgm.kit.trace.TraceFilter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Веб-часть каркаса на прикладном порту: сквозной идентификатор, проверка токена по правилам маршрутов, проверка вызывающего
 * сервиса, ответы об ошибке по RFC 9457. Фильтры работают только на порту 8443: порт метрик и здоровья живёт в отдельном контексте.
 */
@AutoConfiguration(after = KitCoreAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KitWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    RoutePolicy routePolicy(KitProperties properties) {
        return RoutePolicy.fromClasspath(properties.routes());
    }

    /** Проверка токена по ключам Keycloak; ключи грузятся лениво при первом токене, поэтому Keycloak при старте сервису не нужен. */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(KitProperties properties, TlsMaterial tls, Clock clock) {
        KitProperties.Jwt jwt = properties.security().jwt();
        if (jwt.issuer() == null || jwt.jwksUri() == null) {
            throw new IllegalStateException("Не заданы dgm.security.jwt.issuer и dgm.security.jwt.jwks-uri");
        }
        JwtSettings settings = new JwtSettings(jwt.issuer(), jwt.audience(), jwt.jwksUri(), JwtSettings.DEFAULT_SKEW);
        return JwtDecoders.forJwkSet(settings, tls.trustOnlyContext(), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemAdvice problemAdvice() {
        return new ProblemAdvice();
    }

    @Bean
    FilterRegistrationBean<TraceFilter> traceFilterRegistration() {
        FilterRegistrationBean<TraceFilter> registration = new FilterRegistrationBean<>(new TraceFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    FilterRegistrationBean<JwtFilter> jwtFilterRegistration(RoutePolicy policy, JwtDecoder decoder) {
        FilterRegistrationBean<JwtFilter> registration = new FilterRegistrationBean<>(new JwtFilter(policy, decoder));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    FilterRegistrationBean<CallerFilter> callerFilterRegistration() {
        FilterRegistrationBean<CallerFilter> registration = new FilterRegistrationBean<>(new CallerFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
