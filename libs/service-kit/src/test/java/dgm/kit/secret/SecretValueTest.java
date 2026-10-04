package dgm.kit.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretValueTest {

    private static final String CANARY = "canary-9f3a7c1e-NEVER-PRINT";

    @Test
    void toStringNeverShowsValue() {
        SecretValue secret = SecretValue.of(CANARY);
        assertEquals("SecretValue[****]", secret.toString());
        assertFalse(("" + secret).contains(CANARY));
        assertFalse(String.format("%s|%s", secret, secret).contains(CANARY));
        assertFalse(java.util.List.of(secret).toString().contains(CANARY));
    }

    @Test
    void revealReturnsOriginalValue() {
        SecretValue secret = SecretValue.of(CANARY);
        assertEquals(CANARY, secret.reveal());
        assertArrayEquals(CANARY.getBytes(StandardCharsets.UTF_8), secret.revealBytes());
        assertEquals(CANARY.length(), secret.length());
    }

    @Test
    void revealedBytesAreACopy() {
        SecretValue secret = SecretValue.of("abc");
        byte[] copy = secret.revealBytes();
        copy[0] = 'x';
        assertEquals("abc", secret.reveal());
    }

    @Test
    void inputBytesAreCopiedOnCreation() {
        byte[] input = "abc".getBytes(StandardCharsets.UTF_8);
        SecretValue secret = SecretValue.ofBytes(input);
        input[0] = 'x';
        assertEquals("abc", secret.reveal());
    }

    @Test
    void equalsComparesContentAndHashCodeDoesNotLeak() {
        assertEquals(SecretValue.of("a"), SecretValue.of("a"));
        assertNotEquals(SecretValue.of("a"), SecretValue.of("b"));
        assertNotEquals(SecretValue.of("a"), "a");
        assertEquals(SecretValue.of("a").hashCode(), SecretValue.of("b").hashCode());
    }

    @Test
    void destroyClearsValue() {
        SecretValue secret = SecretValue.of("abc");
        secret.destroy();
        assertEquals("\0\0\0", secret.reveal());
    }

    @Test
    void isNotSerializable() {
        assertFalse(Serializable.class.isAssignableFrom(SecretValue.class));
    }

    @Test
    void readsFileWithoutTrailingNewline(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("db_password"), "s3cret-value\r\n");
        SecretValue secret = SecretFiles.read(dir.resolve("db_password"));
        assertEquals("s3cret-value", secret.reveal());
    }

    @Test
    void emptyFileIsRejected(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("empty"), "\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> SecretFiles.read(dir.resolve("empty")));
        assertFalse(e.getMessage().contains(dir.toString()), "в сообщении нет полного пути");
        assertTrue(e.getMessage().contains("empty"));
    }

    @Test
    void missingFileIsReportedWithoutValue(@TempDir Path dir) {
        assertThrows(java.io.UncheckedIOException.class, () -> SecretFiles.read(dir.resolve("absent")));
    }

    @Test
    void directoryComesFromSystemProperty(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("kek"), "abc");
        String before = System.getProperty("dgm.secrets.dir");
        System.setProperty("dgm.secrets.dir", dir.toString());
        try {
            assertEquals("abc", SecretFiles.read("kek").reveal());
        } finally {
            if (before == null) {
                System.clearProperty("dgm.secrets.dir");
            } else {
                System.setProperty("dgm.secrets.dir", before);
            }
        }
    }
}
