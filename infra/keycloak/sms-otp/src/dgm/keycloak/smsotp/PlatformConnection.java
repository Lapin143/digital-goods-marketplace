package dgm.keycloak.smsotp;

import dgm.keycloak.smsotp.core.HttpPlatformOtp;
import dgm.keycloak.smsotp.core.PemTls;
import dgm.keycloak.smsotp.core.PlatformOtp;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import org.jboss.logging.Logger;

/**
 * Подключение Keycloak к платформе: один клиент HTTP на процесс, создаётся при первом входе по SMS. Настройки из окружения контейнера:
 * {@code DGM_PLATFORM_URL} (по умолчанию {@code https://platform-service:8443}) и {@code DGM_TLS_DIR} (по умолчанию
 * {@code /run/secrets}: там лежат {@code tls_keycloak.crt}, {@code tls_keycloak.key}, {@code tls_ca.crt}, Docker уже подключил их для
 * самого сервера Keycloak). Если файлов нет, вход по SMS честно отвечает «временно недоступен», остальные способы входа работают.
 */
final class PlatformConnection {

    private static final Logger LOG = Logger.getLogger(PlatformConnection.class);
    private static final PlatformOtp UNAVAILABLE = new PlatformOtp() {
        @Override
        public Issued issue(String phone, String clientIp) {
            return new Issued.Unavailable();
        }

        @Override
        public Verified verify(String phone, String code) {
            return new Verified.Unavailable();
        }
    };

    private static volatile PlatformOtp instance;

    private PlatformConnection() {
    }

    static PlatformOtp get() {
        PlatformOtp current = instance;
        if (current != null) {
            return current;
        }
        synchronized (PlatformConnection.class) {
            if (instance == null) {
                try {
                    instance = create();
                } catch (IOException | GeneralSecurityException | RuntimeException e) {
                    // Не запоминаем неудачу: следующий вход попробует снова (например, после выпуска сертификатов)
                    LOG.errorf("sms-otp: подключение к platform-service не создано (%s)", e.getClass().getSimpleName());
                    return UNAVAILABLE;
                }
            }
            return instance;
        }
    }

    private static PlatformOtp create() throws IOException, GeneralSecurityException {
        URI base = URI.create(env("DGM_PLATFORM_URL", "https://platform-service:8443"));
        Path dir = Path.of(env("DGM_TLS_DIR", "/run/secrets"));
        SSLContext tls = PemTls.mutual(dir.resolve("tls_keycloak.crt"), dir.resolve("tls_keycloak.key"), dir.resolve("tls_ca.crt"));
        HttpClient client = HttpClient.newBuilder()
                .sslContext(tls)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return new HttpPlatformOtp(client, base, HttpPlatformOtp.randomKeys(), Duration.ofSeconds(5));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
