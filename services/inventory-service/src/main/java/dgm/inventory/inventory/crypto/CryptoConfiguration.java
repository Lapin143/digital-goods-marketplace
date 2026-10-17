package dgm.inventory.inventory.crypto;

import dgm.kit.boot.SecretsDirectory;
import dgm.kit.secret.SecretValue;
import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Секреты шифрования: мастер-ключ {@code inventory_kek} и секрет {@code inventory_hmac} читаются из каталога секретов при старте и
 * держатся в памяти (ADR-009). Нет файла или он неверен: сервис не запускается.
 */
@Configuration(proxyBeanMethods = false)
public class CryptoConfiguration {

    @Bean
    SecureRandom keyRandom() {
        return new SecureRandom();
    }

    @Bean
    KeyCipher keyCipher(SecureRandom keyRandom, SecretsDirectory secrets) {
        SecretValue secret = secrets.read("inventory_kek");
        try {
            return KeyCipher.fromSecret(secret, keyRandom);
        } finally {
            secret.destroy();
        }
    }

    @Bean
    KeyHasher keyHasher(SecretsDirectory secrets) {
        SecretValue secret = secrets.read("inventory_hmac");
        try {
            return KeyHasher.fromSecret(secret);
        } finally {
            secret.destroy();
        }
    }
}
