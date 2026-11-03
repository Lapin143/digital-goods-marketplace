package dgm.kit.crypto;

import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Секрет подписи запросов и webhook для продавца (ADR-028, Р-187). Секрет не хранится: он считается из мастер-секрета платформы
 * (хранилище секретов) как {@code HMAC-SHA-256(мастер, "seller-integration|<идентификатор продавца>|<версия>")}, результат записывается
 * 64 строчными шестнадцатеричными знаками, и именно эта строка служит ключом подписи {@link WebhookSignature}. Продавец видит её один раз
 * в кабинете (при создании и при смене), платформа выводит её заново при каждой отправке.
 *
 * <p>Версия растёт при смене секрета: по прежней версии платформа ещё принимает подписи своего старого ключа на время перехода, а
 * отправляет по новой. Утечка секрета одного продавца не раскрывает мастера и секретов других продавцов.
 */
public final class SellerSecrets {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String CONTEXT = "seller-integration";
    private static final int MIN_MASTER_BYTES = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final SecretValue master;

    public SellerSecrets(SecretValue master) {
        Objects.requireNonNull(master, "master");
        if (master.length() < MIN_MASTER_BYTES) {
            throw new IllegalArgumentException("Мастер-секрет интеграции продавцов короче " + MIN_MASTER_BYTES + " байт.");
        }
        this.master = master;
    }

    /** Секрет продавца заданной версии. Версия начинается с 1. */
    public SecretValue secret(UUID sellerId, int version) {
        Objects.requireNonNull(sellerId, "sellerId");
        if (version < 1) {
            throw new IllegalArgumentException("Версия секрета начинается с 1.");
        }
        byte[] key = master.revealBytes();
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] digest = mac.doFinal((CONTEXT + "|" + sellerId + "|" + version).getBytes(StandardCharsets.UTF_8));
            return SecretValue.of(hex(digest));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA-256 недоступен или мастер-секрет не подходит как ключ", e);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    /** Подпись запроса продавцу: {@code HMAC-SHA-256(секрет, метка + "." + тело)}, hex. */
    public String sign(UUID sellerId, int version, String timestamp, byte[] body) {
        SecretValue secret = secret(sellerId, version);
        byte[] key = secret.revealBytes();
        try {
            return WebhookSignature.sign(key, timestamp, body);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
            secret.destroy();
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }
}
