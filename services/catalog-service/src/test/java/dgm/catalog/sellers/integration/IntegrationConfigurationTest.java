package dgm.catalog.sellers.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.boot.SecretsDirectory;
import dgm.kit.client.EndpointGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import org.springframework.mock.env.MockEnvironment;

/**
 * Подключение защиты адреса и секрета подписи: список разрешённых имён стенда пуст по умолчанию и разбирается через запятую, мастер-секрет
 * читается из каталога секретов, без него или с коротким сервис не запускается.
 */
@Tag("TC-954")
@Tag("TC-959")
class IntegrationConfigurationTest {

    private final IntegrationConfiguration configuration = new IntegrationConfiguration();

    private static MockEnvironment environment(String allow) {
        MockEnvironment environment = new MockEnvironment();
        if (allow != null) {
            environment.setProperty(IntegrationConfiguration.ALLOW_PROPERTY, allow);
        }
        return environment;
    }

    @Test
    void theAllowListIsEmptyByDefaultAndSplitsOnCommasIgnoringBlanks() {
        assertEquals(Set.of(), IntegrationConfiguration.allowed(""));
        assertEquals(Set.of(), IntegrationConfiguration.allowed(" , ,"));
        assertEquals(Set.of("seller-stub", "seller-stub:8443", "other"), IntegrationConfiguration.allowed("seller-stub, seller-stub:8443 ,,other"));
    }

    @Test
    void withoutTheSettingNoNameIsFreedFromTheRangeCheck() {
        EndpointGuard guard = configuration.endpointGuard(environment(null));
        assertEquals(EndpointGuard.Reason.FORBIDDEN_ADDRESS, assertThrows(EndpointGuard.RejectedException.class,
                () -> guard.check("https://127.0.0.1/dgm")).reason());
    }

    @Test
    void aListedNameIsFreedFromTheRangeCheckOnly() {
        EndpointGuard guard = configuration.endpointGuard(environment("localhost"));
        assertEquals("localhost", guard.check("https://localhost:8443/dgm").host());
        assertEquals(EndpointGuard.Reason.NOT_HTTPS, assertThrows(EndpointGuard.RejectedException.class,
                () -> guard.check("http://localhost:8443/dgm")).reason());
        assertEquals(EndpointGuard.Reason.FORBIDDEN_ADDRESS, assertThrows(EndpointGuard.RejectedException.class,
                () -> guard.check("https://127.0.0.1:8443/dgm")).reason());
    }

    /** Каталог секретов во временной папке; файл мастер-секрета создаётся, если дано содержимое. Папка убирается после проверки. */
    private static void withDirectory(String content, Consumer<SecretsDirectory> check) throws IOException {
        Path dir = Files.createTempDirectory("dgm-secrets-" + UUID.randomUUID());
        Path file = dir.resolve(IntegrationConfiguration.MASTER_SECRET);
        try {
            if (content != null) {
                Files.writeString(file, content, StandardCharsets.UTF_8);
            }
            check.accept(new SecretsDirectory(dir));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void theMasterSecretIsReadFromTheSecretsDirectoryAndGivesTheSameSecretEveryTime() throws IOException {
        UUID seller = UUID.fromString("5b7f0e1a-3c4d-4e5f-8a6b-7c8d9e0f1a2b");
        withDirectory("a-master-secret-of-sufficient-length\n", directory -> {
            var secrets = configuration.integrationSecrets(directory);
            assertEquals(secrets.reveal(seller, 1), secrets.reveal(seller, 1));
            assertEquals(64, secrets.reveal(seller, 1).length());
        });
    }

    @Test
    void theServiceDoesNotStartWithoutTheMasterSecretOrWithATooShortOne() throws IOException {
        withDirectory(null, directory -> assertThrows(RuntimeException.class, () -> configuration.integrationSecrets(directory)));
        withDirectory("short", directory -> assertThrows(IllegalArgumentException.class, () -> configuration.integrationSecrets(directory)));
    }
}
