package dgm.keycloak.smsotp.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Контекст TLS из файлов PEM, которые Docker кладёт в {@code /run/secrets}: сертификат и ключ сервиса (PKCS #8) и сертификат частного
 * центра. Сервер платформы требует клиентский сертификат и по его CN решает, кому разрешён внутренний вызов (ADR-022): Keycloak
 * представляется как {@code keycloak}. Доверие ограничено центром проекта, системные центры не используются.
 */
public final class PemTls {

    private static final String BEGIN_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_KEY = "-----END PRIVATE KEY-----";
    private static final char[] NO_PASSWORD = new char[0];

    private PemTls() {
    }

    /**
     * Контекст с клиентским сертификатом и доверием только к центру проекта.
     *
     * @throws IOException              файл не читается
     * @throws GeneralSecurityException файл не похож на PEM нужного вида
     */
    public static SSLContext mutual(Path certificate, Path privateKey, Path authority) throws IOException, GeneralSecurityException {
        Collection<? extends Certificate> chain = certificates(Files.readString(certificate, StandardCharsets.US_ASCII));
        if (chain.isEmpty()) {
            throw new GeneralSecurityException("В файле сертификата нет сертификатов");
        }
        PrivateKey key = privateKey(Files.readString(privateKey, StandardCharsets.US_ASCII));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("client", key, NO_PASSWORD, chain.toArray(new Certificate[0]));
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, NO_PASSWORD);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        int index = 0;
        for (Certificate ca : certificates(Files.readString(authority, StandardCharsets.US_ASCII))) {
            trust.setCertificateEntry("ca-" + index++, ca);
        }
        if (index == 0) {
            throw new GeneralSecurityException("В файле центра сертификации нет сертификатов");
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
        return context;
    }

    /** Сертификаты из текста PEM (один или цепочка). */
    static Collection<? extends Certificate> certificates(String pem) throws GeneralSecurityException {
        return CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * Закрытый ключ в виде PKCS #8 ({@code BEGIN PRIVATE KEY}), EC или RSA. Другие виды (SEC1, зашифрованный) отклоняются с понятным
     * сообщением: infra/pki выпускает именно PKCS #8.
     */
    static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        int begin = pem.indexOf(BEGIN_KEY);
        int end = pem.indexOf(END_KEY);
        if (begin < 0 || end < begin) {
            throw new GeneralSecurityException("Ожидался закрытый ключ PKCS #8 (BEGIN PRIVATE KEY)");
        }
        String body = pem.substring(begin + BEGIN_KEY.length(), end).replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Ключ не разобран: неверная запись Base64", e);
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : new String[] {"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException e) {
                // пробуем следующий алгоритм
            }
        }
        throw new GeneralSecurityException("Ключ не разобран: ни EC, ни RSA");
    }
}
