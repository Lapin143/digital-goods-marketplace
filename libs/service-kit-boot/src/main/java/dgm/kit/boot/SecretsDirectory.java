package dgm.kit.boot;

import dgm.kit.secret.SecretFiles;
import dgm.kit.secret.SecretValue;
import java.nio.file.Path;

/** Каталог секретов сервиса: свойство {@code dgm.secrets.dir}, переменная {@code DGM_SECRETS_DIR} или {@code /run/secrets}. */
public record SecretsDirectory(Path path) {

    public SecretValue read(String name) {
        return SecretFiles.read(path.resolve(name));
    }
}
