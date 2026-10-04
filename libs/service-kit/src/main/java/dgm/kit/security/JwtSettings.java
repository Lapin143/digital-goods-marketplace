package dgm.kit.security;

import java.time.Duration;
import java.util.Objects;

/**
 * Настройки проверки токена.
 *
 * @param issuer     издатель {@code iss}: публичный адрес realm, например https://localhost:8443/auth/realms/dgm
 * @param audience   адресат {@code aud}: dgm-api
 * @param jwkSetUri  адрес ключей Keycloak, по которому сервис ходит внутри сети (адрес издателя и адрес ключей разные)
 * @param clockSkew  допуск расхождения часов при проверке срока
 */
public record JwtSettings(String issuer, String audience, String jwkSetUri, Duration clockSkew) {

    public static final String DEFAULT_AUDIENCE = "dgm-api";
    public static final Duration DEFAULT_SKEW = Duration.ofSeconds(30);

    public JwtSettings {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(clockSkew, "clockSkew");
    }

    public JwtSettings(String issuer, String jwkSetUri) {
        this(issuer, DEFAULT_AUDIENCE, jwkSetUri, DEFAULT_SKEW);
    }
}
