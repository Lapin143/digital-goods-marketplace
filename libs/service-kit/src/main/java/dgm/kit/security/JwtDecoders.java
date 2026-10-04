package dgm.kit.security;

import dgm.kit.time.Clocks;
import java.net.http.HttpClient;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/** Сборка декодера токена: подпись по ключам Keycloak (JWKS по TLS с нашим центром), срок, издатель и адресат. */
public final class JwtDecoders {

    private JwtDecoders() {
    }

    /** Декодер для работы: ключи берутся по {@code jwkSetUri}, соединение проверяется контекстом TLS с нашим центром сертификации. */
    public static JwtDecoder forJwkSet(JwtSettings settings, SSLContext tls) {
        return forJwkSet(settings, tls, Clocks.system());
    }

    /** То же с заданными часами: срок токена сверяется с ними (в тестах это управляемые часы). */
    public static JwtDecoder forJwkSet(JwtSettings settings, SSLContext tls, Clock clock) {
        HttpClient client = HttpClient.newBuilder().sslContext(tls).build();
        RestTemplate rest = new RestTemplate(new JdkClientHttpRequestFactory(client));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(settings.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(rest)
                .build();
        decoder.setJwtValidator(validator(settings, clock));
        return decoder;
    }

    /** Декодер с одним открытым ключом: для тестов, где токены подписывает сам тест. */
    public static JwtDecoder forPublicKey(RSAPublicKey key, JwtSettings settings, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).build();
        decoder.setJwtValidator(validator(settings, clock));
        return decoder;
    }

    /** Проверки claims: срок (по заданным часам), издатель, адресат. Подпись проверяет сам декодер. */
    public static OAuth2TokenValidator<Jwt> validator(JwtSettings settings, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(settings.clockSkew());
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            List<String> aud = jwt.getAudience();
            if (aud != null && aud.contains(settings.audience())) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Токен выдан для другого адресата", null));
        };
        List<OAuth2TokenValidator<Jwt>> all = List.of(timestamps, new JwtIssuerValidator(settings.issuer()), audience);
        return new DelegatingOAuth2TokenValidator<>(all);
    }
}
