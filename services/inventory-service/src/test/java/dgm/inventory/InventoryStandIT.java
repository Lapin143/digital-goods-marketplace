package dgm.inventory;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.testing.TestTokens;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Общая основа интеграционных тестов inventory-service на стенде: один запущенный сервис на все классы (второй экземпляр в том же
 * процессе не получится: публикатор Outbox держит блокировку лидера в базе, а у ролей базы есть пределы числа соединений). Порты, токены
 * и набор свойств заданы здесь, классы-наследники наполняют тесты, Spring повторно использует контекст.
 *
 * <p>Нужен набор стенда {@code dev-kit} с отладочными портами: {@code make up SET=dev-kit DEBUG=1}. Секреты {@code inventory_kek} и
 * {@code inventory_hmac} сервис читает из каталога секретов стенда.
 */
@Tag("integration")
@SpringBootTest(classes = InventoryServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(InventoryStandIT.TestBeans.class)
abstract class InventoryStandIT {

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
    }
}
