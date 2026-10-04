package dgm.kit.tls;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Разбор PEM: сертификаты X.509 и закрытый ключ в формате PKCS#8 (так их выпускает infra/pki, так их читает и Kafka). */
public final class Pem {

    private static final String KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String KEY_END = "-----END PRIVATE KEY-----";
    private static final String[] KEY_ALGORITHMS = {"EC", "RSA", "Ed25519"};

    private Pem() {
    }

    /** Все сертификаты из PEM-текста; пусто или мусор вместо сертификата считается ошибкой. */
    public static List<X509Certificate> certificates(String pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> result = new ArrayList<>();
            for (Certificate c : factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
                result.add((X509Certificate) c);
            }
            if (result.isEmpty()) {
                throw new IllegalArgumentException("В PEM нет ни одного сертификата");
            }
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Сертификат не разбирается", e);
        }
    }

    /** Закрытый ключ PKCS#8 без пароля. Ключ другого формата (BEGIN EC PRIVATE KEY, BEGIN ENCRYPTED PRIVATE KEY) отклоняется. */
    public static PrivateKey privateKey(String pem) {
        int begin = pem.indexOf(KEY_BEGIN);
        int end = pem.indexOf(KEY_END);
        if (begin < 0 || end < begin) {
            throw new IllegalArgumentException("Нужен закрытый ключ PKCS#8 без пароля (BEGIN PRIVATE KEY)");
        }
        String body = pem.substring(begin + KEY_BEGIN.length(), end).replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException e) {
                // Другой алгоритм: пробуем следующий
                continue;
            }
        }
        throw new IllegalArgumentException("Алгоритм закрытого ключа не поддерживается");
    }
}
