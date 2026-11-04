package dgm.catalog.sellers.integration.crypto;

import dgm.kit.crypto.SellerSecrets;
import dgm.kit.secret.SecretValue;
import java.util.Objects;
import java.util.UUID;

/**
 * Секрет подписи продавца в виде строки для кабинета. Читает значение {@link SecretValue} только здесь, в пакете {@code crypto}: так
 * правило архитектуры о чтении секретов видит единственное место, где секрет покидает хранилище, и это место показывает его продавцу один раз
 * (ответ первого {@code setSellerIntegration} и {@code rotateSellerIntegrationSecret}).
 */
public final class IntegrationSecrets {

    private final SellerSecrets secrets;

    public IntegrationSecrets(SellerSecrets secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** Секрет продавца заданной версии: 64 строчных шестнадцатеричных знака, один и тот же в трёх сервисах (контрольный вектор в {@code SellerSecretsTest}). */
    public String reveal(UUID sellerId, int version) {
        SecretValue secret = secrets.secret(sellerId, version);
        try {
            return secret.reveal();
        } finally {
            secret.destroy();
        }
    }
}
