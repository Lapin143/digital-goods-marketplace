package dgm.kit.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Токены для тестов: подписываются парой ключей RSA, созданной тестом, и по набору claims повторяют токены Keycloak проекта
 * (iss, aud, scope, realm_access.roles, amr, phone_verified). Открытый ключ идёт в {@code JwtDecoders.forPublicKey}.
 */
public final class TestTokens {

    public static final String ISSUER = "https://localhost:8443/auth/realms/dgm";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final Clock clock;

    private TestTokens(KeyPair pair, Clock clock) {
        this.privateKey = (RSAPrivateKey) pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.clock = clock;
    }

    public static TestTokens create(Clock clock) {
        return new TestTokens(newPair(), clock);
    }

    /** Ещё один выпускающий центр: токен, подписанный им, декодер с ключом первого отклонит. */
    public static KeyPair newPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    /** Заготовка токена обычного покупателя: полный вход, область orders.read, срок 5 минут. */
    public Spec token() {
        return new Spec(this);
    }

    /** Параметры токена; методы возвращают тот же объект. */
    public static final class Spec {

        private final TestTokens owner;
        private String subject = "11111111-1111-4111-8111-111111111111";
        private String issuer = ISSUER;
        private List<String> audience = List.of("dgm-api");
        private List<String> scopes = List.of("orders.read");
        private List<String> roles = List.of("buyer");
        private List<String> amr = List.of("pwd");
        private boolean phoneVerified = true;
        private Duration expiresIn = Duration.ofMinutes(5);
        private RSAPrivateKey signWith;

        private Spec(TestTokens owner) {
            this.owner = owner;
            this.signWith = owner.privateKey;
        }

        public Spec subject(String value) {
            this.subject = value;
            return this;
        }

        public Spec issuer(String value) {
            this.issuer = value;
            return this;
        }

        public Spec audience(String... values) {
            this.audience = List.of(values);
            return this;
        }

        public Spec scopes(String... values) {
            this.scopes = List.of(values);
            return this;
        }

        public Spec roles(String... values) {
            this.roles = List.of(values);
            return this;
        }

        public Spec amr(String... values) {
            this.amr = List.of(values);
            return this;
        }

        public Spec phoneVerified(boolean value) {
            this.phoneVerified = value;
            return this;
        }

        /** Срок действия от текущего момента часов; отрицательный даёт уже просроченный токен. */
        public Spec expiresIn(Duration value) {
            this.expiresIn = value;
            return this;
        }

        /** Подписать чужим закрытым ключом. */
        public Spec signedWith(KeyPair other) {
            this.signWith = (RSAPrivateKey) other.getPrivate();
            return this;
        }

        /** Токен без подписи (алгоритм none): декодер обязан его отклонить. */
        public String buildUnsigned() {
            return new PlainJWT(claims()).serialize();
        }

        public String build() {
            JWTClaimsSet claims = claims();
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
                jwt.sign(new RSASSASigner(signWith));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException("Токен не подписан", e);
            }
        }

        private JWTClaimsSet claims() {
            Map<String, Object> realmAccess = new LinkedHashMap<>();
            realmAccess.put("roles", roles);
            return new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(owner.clock.instant()))
                    .expirationTime(Date.from(owner.clock.instant().plus(expiresIn)))
                    .claim("scope", String.join(" ", scopes))
                    .claim("realm_access", realmAccess)
                    .claim("amr", amr)
                    .claim("phone_verified", phoneVerified)
                    .build();
        }
    }
}
