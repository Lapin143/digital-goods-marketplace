package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Взаимный TLS из файлов PEM. Ключи и сертификат создаются в тесте (keytool из JDK), в репозитории закрытых ключей нет. Сертификат сам
 * себе центр, в нём CN=keycloak и SAN localhost: так сервер платформы определяет вызывающего по CN (ADR-022).
 */
class PemTlsTest {

    private Path dir;
    private HttpsServer server;
    private final AtomicReference<String> clientCn = new AtomicReference<>();

    @BeforeEach
    void prepare() throws Exception {
        dir = Files.createTempDirectory("pemtls");
        Path store = dir.resolve("store.p12");
        keytool("-genkeypair", "-alias", "keycloak", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=keycloak,O=test", "-validity", "2",
                "-ext", "san=dns:localhost,ip:127.0.0.1", "-ext", "eku=serverAuth,clientAuth", "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", "changeit");
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        X509Certificate certificate = (X509Certificate) keys.getCertificate("keycloak");
        PrivateKey key = (PrivateKey) keys.getKey("keycloak", "changeit".toCharArray());
        Files.writeString(dir.resolve("tls.crt"), pem("CERTIFICATE", certificate.getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(dir.resolve("tls.key"), pem("PRIVATE KEY", key.getEncoded()), StandardCharsets.US_ASCII);

        // Сервер платформы: тот же сертификат, клиентский сертификат обязателен
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "changeit".toCharArray());
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", certificate);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters parameters = getSSLContext().getDefaultSSLParameters();
                parameters.setNeedClientAuth(true);
                params.setSSLParameters(parameters);
            }
        });
        server.createContext("/", exchange -> {
            try {
                var session = ((com.sun.net.httpserver.HttpsExchange) exchange).getSSLSession();
                clientCn.set(((X509Certificate) session.getPeerCertificates()[0]).getSubjectX500Principal().getName());
            } catch (SSLPeerUnverifiedException e) {
                clientCn.set("none");
            }
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void cleanup() throws IOException {
        server.stop(0);
        try (var files = Files.list(dir)) {
            files.forEach(f -> f.toFile().delete());
        }
        Files.delete(dir);
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static String pem(String type, byte[] der) {
        return pemText(type, Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der));
    }

    private static String pemText(String type, String body) {
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    @Test
    void mutualTlsRoundTripPresentsClientCertificateWithCn() throws Exception {
        SSLContext tls = PemTls.mutual(dir.resolve("tls.crt"), dir.resolve("tls.key"), dir.resolve("tls.crt"));
        HttpPlatformOtp otp = new HttpPlatformOtp(HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).build(),
                URI.create("https://localhost:" + server.getAddress().getPort()), HttpPlatformOtp.randomKeys(), Duration.ofSeconds(5));
        assertInstanceOf(PlatformOtp.Issued.Accepted.class, otp.issue("+79001234567", null));
        assertTrue(clientCn.get().contains("CN=keycloak"), clientCn.get());
    }

    @Test
    void serverWithForeignCertificateIsNotTrusted() throws Exception {
        // Доверие только к центру проекта: чужой сертификат сервера (здесь сертификат другого ключа) вызов не пропускает
        Path other = Files.createTempDirectory("pemtls-other");
        try {
            Path store = other.resolve("store.p12");
            keytool("-genkeypair", "-alias", "other", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=other", "-validity", "2",
                    "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keys.load(in, "changeit".toCharArray());
            }
            Files.writeString(other.resolve("ca.crt"), pem("CERTIFICATE", keys.getCertificate("other").getEncoded()), StandardCharsets.US_ASCII);
            SSLContext tls = PemTls.mutual(dir.resolve("tls.crt"), dir.resolve("tls.key"), other.resolve("ca.crt"));
            HttpPlatformOtp otp = new HttpPlatformOtp(HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1).build(),
                    URI.create("https://localhost:" + server.getAddress().getPort()), HttpPlatformOtp.randomKeys(), Duration.ofSeconds(5));
            assertInstanceOf(PlatformOtp.Issued.Unavailable.class, otp.issue("+79001234567", null));
        } finally {
            try (var files = Files.list(other)) {
                files.forEach(f -> f.toFile().delete());
            }
            Files.delete(other);
        }
    }

    @Test
    void pkcs8KeyOfEcAndRsaIsParsed() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        PrivateKey ecKey = ec.generateKeyPair().getPrivate();
        assertEquals("EC", PemTls.privateKey(pem("PRIVATE KEY", ecKey.getEncoded())).getAlgorithm());
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        PrivateKey rsaKey = rsa.generateKeyPair().getPrivate();
        assertEquals("RSA", PemTls.privateKey(pem("PRIVATE KEY", rsaKey.getEncoded())).getAlgorithm());
    }

    @Test
    void otherKeyFormatsAreRejectedWithClearMessage() {
        // Тексты собираются из частей: целиком такая строка похожа на закрытый ключ, и сканер секретов реагирует на неё
        GeneralSecurityException sec1 = assertThrows(GeneralSecurityException.class, () -> PemTls.privateKey(pem("EC PRIVATE KEY", new byte[] {0, 0, 0})));
        assertTrue(sec1.getMessage().contains("PKCS #8"), sec1.getMessage());
        assertThrows(GeneralSecurityException.class, () -> PemTls.privateKey(pemText("PRIVATE KEY", "!!!")));
        assertThrows(GeneralSecurityException.class, () -> PemTls.privateKey(pem("PRIVATE KEY", new byte[] {0, 0, 0})));
    }

    @Test
    void emptyCertificateFilesAreRejected() throws Exception {
        Files.writeString(dir.resolve("empty.crt"), "", StandardCharsets.US_ASCII);
        assertThrows(GeneralSecurityException.class, () -> PemTls.mutual(dir.resolve("empty.crt"), dir.resolve("tls.key"), dir.resolve("tls.crt")));
        assertThrows(GeneralSecurityException.class, () -> PemTls.mutual(dir.resolve("tls.crt"), dir.resolve("tls.key"), dir.resolve("empty.crt")));
        assertThrows(IOException.class, () -> PemTls.mutual(dir.resolve("missing.crt"), dir.resolve("tls.key"), dir.resolve("tls.crt")));
    }
}
