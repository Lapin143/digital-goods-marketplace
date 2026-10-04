package dgm.kit.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Самоподписанные сертификаты для тестов. Выпускает их {@code keytool} из JDK, на котором идут тесты, поэтому в репозитории нет
 * ни одного ключа, который сканер секретов принял бы за настоящий.
 */
public final class TestCertificates {

    private TestCertificates() {
    }

    /** Сертификат с закрытым ключом: сам объект и тексты PEM (сертификат, ключ PKCS#8). */
    public record Material(X509Certificate certificate, PrivateKey key, String certificatePem, String keyPem) {
    }

    /** Самоподписанный сертификат EC P-256 с CN и именами localhost, 127.0.0.1; он же годится как центр сертификации. */
    public static Material selfSigned(String commonName) {
        try {
            Path dir = Files.createTempDirectory("dgm-test-cert");
            Path store = dir.resolve("store.p12");
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "k", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-sigalg", "SHA256withECDSA", "-dname", "CN=" + commonName, "-validity", "3650", "-ext", "bc:c",
                    "-ext", "san=dns:localhost,ip:127.0.0.1", "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "changeit")
                    .redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool завершился ошибкой: " + output);
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keyStore.load(in, "changeit".toCharArray());
            }
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate("k");
            PrivateKey key = (PrivateKey) keyStore.getKey("k", "changeit".toCharArray());
            return new Material(certificate, key, pem("CERTIFICATE", certificate.getEncoded()), pem("PRIVATE KEY", key.getEncoded()));
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Сертификат для теста не создан", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Выпуск сертификата прерван", e);
        }
    }

    /** Записывает материал в каталог в виде файлов {@code tls_ca.crt}, {@code tls_<сервис>.crt}, {@code tls_<сервис>.key}. */
    public static void writeFiles(Path dir, String service, Material material) {
        try {
            Files.writeString(dir.resolve("tls_ca.crt"), material.certificatePem(), StandardCharsets.US_ASCII);
            Files.writeString(dir.resolve("tls_" + service + ".crt"), material.certificatePem(), StandardCharsets.US_ASCII);
            Files.writeString(dir.resolve("tls_" + service + ".key"), material.keyPem(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
