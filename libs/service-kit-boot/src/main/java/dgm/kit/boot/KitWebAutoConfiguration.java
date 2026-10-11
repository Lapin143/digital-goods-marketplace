package dgm.kit.boot;

import dgm.kit.idempotency.IdempotencyFilter;
import dgm.kit.problem.ProblemAdvice;
import dgm.kit.route.RoutePolicy;
import dgm.kit.route.RouteRule;
import dgm.kit.secret.SecretValue;
import dgm.kit.security.CallerFilter;
import dgm.kit.security.JwtDecoders;
import dgm.kit.security.JwtFilter;
import dgm.kit.security.JwtSettings;
import dgm.kit.security.WebhookSecrets;
import dgm.kit.security.WebhookSignatureFilter;
import dgm.kit.tls.TlsMaterial;
import dgm.kit.trace.AccessLogFilter;
import dgm.kit.trace.TraceFilter;
import dgm.kit.web.BodyLimitFilter;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Веб-часть каркаса на прикладном порту: сквозной идентификатор, журнал обращений, проверка токена по правилам маршрутов, предел размера
 * тела, проверка вызывающего сервиса, подпись вебхука, ключ идемпотентности, ответы об ошибке по RFC 9457. Фильтры работают только на
 * порту 8443: порт метрик и здоровья живёт в отдельном контексте.
 *
 * <p>Порядок фильтров: сквозной идентификатор, журнал (+5), токен и поиск маршрута (+10), предел тела (+15), вызывающий сервис (+20), подпись
 * вебхука (+25), ключ идемпотентности (+30). Последние три читают правило маршрута, которое положил фильтр токена.
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

    /** Сразу после сквозного идентификатора и до проверки токена: в журнал попадают и отказы фильтров безопасности. */
    @Bean
    FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(Clock clock) {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(new AccessLogFilter(clock));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
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

    /** После поиска маршрута (предел вебхука меньше) и до чтения тела любым последующим фильтром. */
    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimitFilterRegistration(KitProperties properties) {
        KitProperties.Web web = properties.web();
        FilterRegistrationBean<BodyLimitFilter> registration = new FilterRegistrationBean<>(
                new BodyLimitFilter(web.maxBody().toBytes(), web.webhookMaxBody().toBytes()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
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

    /**
     * Секреты подписи вебхуков из каталога секретов: файл с именем из правила маршрута и, если он есть, файл с суффиксом
     * {@code _previous} (смена ключа без простоя, ADR-015: допустимы два действующих секрета).
     */
    @Bean
    @ConditionalOnMissingBean
    WebhookSecrets webhookSecrets(SecretsDirectory secrets) {
        return name -> {
            List<SecretValue> found = new ArrayList<>();
            for (String file : List.of(name, name + "_previous")) {
                if (Files.isRegularFile(secrets.path().resolve(file))) {
                    found.add(secrets.read(file));
                }
            }
            return found;
        };
    }

    @Bean
    FilterRegistrationBean<WebhookSignatureFilter> webhookSignatureFilterRegistration(KitProperties properties, WebhookSecrets secrets,
                                                                                      Clock clock) {
        FilterRegistrationBean<WebhookSignatureFilter> registration = new FilterRegistrationBean<>(
                new WebhookSignatureFilter(secrets, clock, properties.web().webhookMaxBody().toBytes()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 25);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** Ключ идемпотентности: охранник берётся у модуля маршрута, а без указания модуля у модуля по умолчанию. */
    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(KitProperties properties, RoutePolicy policy,
                                                                            ObjectProvider<Modules> modulesProvider) {
        Modules modules = modulesProvider.getIfAvailable();
        if (modules == null && policy.rules().stream().anyMatch(RouteRule::idempotent)) {
            throw new IllegalStateException("В правилах маршрутов есть операции с Idempotency-Key, а база сервиса не подключена (dgm.db.database)");
        }
        String fallback = properties.idempotency().module();
        IdempotencyFilter filter = new IdempotencyFilter(rule -> {
            if (modules == null) {
                throw new IllegalStateException("Охранник идемпотентности требует базу сервиса");
            }
            return modules.idempotency(rule.module() != null ? rule.module() : fallback);
        }, properties.web().maxBody().toBytes());
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
