package dgm.kit.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretPropertiesTest {

    @TempDir
    Path dir;

    @Test
    void readsSecretFileWithoutTrailingNewline() throws IOException {
        Files.writeString(dir.resolve("redis_gateway"), "s3cret-value\n");

        Map<String, Object> properties = SecretProperties.fromFiles(dir, Map.of("spring.data.redis.password", "redis_gateway"));

        assertEquals("s3cret-value", properties.get("spring.data.redis.password"));
    }

    @Test
    void missingFileGivesNoProperty() {
        Map<String, Object> properties = SecretProperties.fromFiles(dir, Map.of("spring.data.redis.password", "redis_gateway"));

        assertFalse(properties.containsKey("spring.data.redis.password"));
    }
}
