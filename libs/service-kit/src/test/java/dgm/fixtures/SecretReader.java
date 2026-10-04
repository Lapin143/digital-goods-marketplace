package dgm.fixtures;

import dgm.kit.secret.SecretValue;

/** Нарушение: значение секрета читается вне разрешённых пакетов. */
public final class SecretReader {

    public String read(SecretValue secret) {
        return secret.reveal();
    }
}
