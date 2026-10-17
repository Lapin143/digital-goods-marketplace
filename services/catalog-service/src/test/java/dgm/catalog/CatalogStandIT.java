package dgm.catalog;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.testing.Stand;
import dgm.kit.testing.TestTokens;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Общая основа интеграционных тестов каталога на стенде: один запущенный сервис на все классы. Второй экземпляр приложения в том же
 * процессе не получится: публикатор Outbox держит блокировку лидера в базе, а у ролей базы есть пределы числа соединений
 * ({@code infra/postgres/roles.json}). Поэтому порты, токены и набор свойств заданы здесь, а классы-наследники только наполняют
 * тесты: Spring видит одну и ту же конфигурацию и повторно использует контекст.
 *
 * <p>Нужны наборы стенда {@code dev-kit} (хранилище) и {@code infra} с базой каталога: {@code make up SET=dev-kit DEBUG=1}.
 */
@Tag("integration")
@SpringBootTest(classes = CatalogServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("testdata")
@Import(CatalogStandIT.TestBeans.class)
abstract class CatalogStandIT {

    static final int PORT = StandHttp.freePort();
    static final int MANAGEMENT = StandHttp.freePort();
    static final TestTokens TOKENS = TestTokens.create(Clock.systemUTC());

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        JwtDecoder jwtDecoder() {
            return StandHttp.decoderFor(TOKENS);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        StandHttp.register(registry, PORT, MANAGEMENT);
        // Хранилище с хоста: отладочный порт набора storage. Ссылки подписываются для имени шлюза, как у пользователя
        registry.add("dgm.storage.endpoint", Stand::storageEndpoint);
        registry.add("dgm.storage.public-base-url", () -> "https://localhost:8443/files");
        // Redis набора infra с хоста: отладочный порт, пользователь ACL catalog (кэш карточек витрины)
        registry.add("dgm.redis.host", () -> env("DGM_REDIS_HOST", "localhost"));
        registry.add("dgm.redis.port", () -> env("DGM_REDIS_PORT", "16379"));
        // Задание контроля сроков в тестах запускают вручную: фоновый запуск мешал бы подсчёту помеченных заявок
        registry.add("dgm.moderation.deadline-initial-delay", () -> "PT24H");
        registry.add("dgm.moderation.deadline-interval", () -> "PT24H");
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
