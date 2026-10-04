package dgm.kit.secret;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Чтение секретов из файлов Docker (/run/secrets/<имя>): в окружении, образе и Git секретов нет (NFT-3.2).
 * Каталог задаёт свойство {@code dgm.secrets.dir} или переменная {@code DGM_SECRETS_DIR}, по умолчанию /run/secrets.
 */
public final class SecretFiles {

    public static final String DEFAULT_DIR = "/run/secrets";

    private SecretFiles() {
    }

    public static Path directory() {
        String dir = System.getProperty("dgm.secrets.dir");
        if (dir == null || dir.isBlank()) {
            dir = System.getenv("DGM_SECRETS_DIR");
        }
        return Path.of(dir == null || dir.isBlank() ? DEFAULT_DIR : dir);
    }

    /** Секрет по имени из каталога секретов. */
    public static SecretValue read(String name) {
        return read(directory().resolve(name));
    }

    /** Секрет из файла. Завершающие переводы строки не входят в значение. */
    public static SecretValue read(Path file) {
        try {
            byte[] raw = Files.readAllBytes(file);
            int end = raw.length;
            while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r')) {
                end--;
            }
            if (end == 0) {
                throw new IllegalStateException("Секрет " + file.getFileName() + " пуст");
            }
            return SecretValue.ofBytes(java.util.Arrays.copyOf(raw, end));
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать секрет " + file.getFileName(), e);
        }
    }
}
