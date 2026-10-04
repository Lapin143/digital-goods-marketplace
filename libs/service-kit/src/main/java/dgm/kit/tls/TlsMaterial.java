package dgm.kit.tls;

import dgm.kit.secret.SecretFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.kafka.common.config.types.Password;

/**
 * Сертификат и ключ сервиса и центр сертификации проекта из секретов ({@code tls_ca.crt}, {@code tls_<сервис>.crt},
 * {@code tls_<сервис>.key}, ADR-022). Из одного места собираются контексты TLS для сервера и клиентов, настройки клиента Kafka
 * (PEM, как в брокере) и строка подключения PostgreSQL с проверкой имени узла. Ключ читается из файла, в журнал и в строки
 * настроек для логов он не попадает.
 */
public final class TlsMaterial {

    private final Path caFile;
    private final Path certFile;
    private final Path keyFile;

    public TlsMaterial(Path caFile, Path certFile, Path keyFile) {
        this.caFile = caFile;
        this.certFile = certFile;
        this.keyFile = keyFile;
    }

    /** Файлы сервиса в каталоге секретов. */
    public static TlsMaterial forService(Path secretsDir, String service) {
        return new TlsMaterial(secretsDir.resolve("tls_ca.crt"), secretsDir.resolve("tls_" + service + ".crt"),
                secretsDir.resolve("tls_" + service + ".key"));
    }

    /** Файлы сервиса в каталоге секретов по умолчанию ({@code DGM_SECRETS_DIR} или {@code /run/secrets}). */
    public static TlsMaterial forService(String service) {
        return forService(SecretFiles.directory(), service);
    }

    public Path caFile() {
        return caFile;
    }

    public Path certFile() {
        return certFile;
    }

    public Path keyFile() {
        return keyFile;
    }

    /** Контекст клиента и сервера: доверяет только нашему центру, предъявляет сертификат сервиса (взаимная проверка). */
    public SSLContext mutualContext() {
        return context(true);
    }

    /** Контекст клиента без собственного сертификата: для узлов, которые клиентского сертификата не требуют (Keycloak). */
    public SSLContext trustOnlyContext() {
        return context(false);
    }

    /**
     * Настройки клиента Kafka: SSL с PEM-материалом, проверка имени узла брокера остаётся включённой. Ключ обёрнут в
     * {@link Password}: в журнал и в {@code toString} он не попадает, как и значения других секретов.
     */
    public Map<String, Object> kafkaProperties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("security.protocol", "SSL");
        p.put("ssl.truststore.type", "PEM");
        p.put("ssl.truststore.certificates", read(caFile));
        p.put("ssl.keystore.type", "PEM");
        p.put("ssl.keystore.certificate.chain", read(certFile));
        p.put("ssl.keystore.key", new Password(read(keyFile)));
        p.put("ssl.endpoint.identification.algorithm", "https");
        return p;
    }

    /** Адрес JDBC с проверкой сертификата и имени узла (sslmode=verify-full), пароль сюда не входит. */
    public String jdbcUrl(String host, int port, String database) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + "?sslmode=verify-full&sslrootcert=" + caFile.toAbsolutePath();
    }

    private SSLContext context(boolean withClientCertificate) {
        try {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            int i = 0;
            for (X509Certificate ca : Pem.certificates(read(caFile))) {
                trust.setCertificateEntry("ca-" + i++, ca);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);

            KeyManagerFactory kmf = null;
            if (withClientCertificate) {
                List<X509Certificate> chain = Pem.certificates(read(certFile));
                PrivateKey key = Pem.privateKey(read(keyFile));
                KeyStore own = KeyStore.getInstance("PKCS12");
                own.load(null, null);
                char[] none = new char[0];
                own.setKeyEntry("service", key, none, chain.toArray(new X509Certificate[0]));
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(own, none);
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Контекст TLS не собран из файлов " + caFile.getFileName() + ", " + certFile.getFileName(), e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException("Файл TLS не читается: " + file.getFileName(), e);
        }
    }
}
