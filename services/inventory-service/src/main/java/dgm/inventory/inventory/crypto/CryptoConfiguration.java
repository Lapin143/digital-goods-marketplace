package dgm.inventory.inventory.crypto;

import dgm.kit.boot.SecretsDirectory;
import dgm.kit.secret.SecretValue;
import java.nio.file.Files;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Секреты шифрования: мастер-ключ {@code inventory_kek}, при ротации ещё {@code inventory_kek_next}, и секрет {@code inventory_hmac}
 * читаются из каталога секретов при старте и держатся в памяти (ADR-009). Нет обязательного файла или он неверен: сервис не запускается.
 * Версия текущего мастер-ключа приходит из настройки {@code dgm.inventory.kek.version} (по умолчанию 1), версия следующего на единицу больше.
 */
@Configuration(proxyBeanMethods = false)
public class CryptoConfiguration {

    @Bean
    SecureRandom keyRandom() {
        return new SecureRandom();
    }

    @Bean
    KekRing kekRing(SecureRandom keyRandom, SecretsDirectory secrets, @Value("${dgm.inventory.kek.version:1}") int version) {
        KeyCipher current = cipher(secrets, "inventory_kek", keyRandom);
        KeyCipher next = Files.exists(secrets.path().resolve("inventory_kek_next")) ? cipher(secrets, "inventory_kek_next", keyRandom) : null;
        return new KekRing(current, version, next);
    }

    /** Шифр значений: сами значения шифруются ключами данных, мастер-ключ ему нужен только для обёртки, ею занимается {@link KekRing}. */
    @Bean
    KeyCipher keyCipher(KekRing ring) {
        return ring.current();
    }

    private static KeyCipher cipher(SecretsDirectory secrets, String name, SecureRandom random) {
        SecretValue secret = secrets.read(name);
        try {
            return KeyCipher.fromSecret(secret, random);
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
