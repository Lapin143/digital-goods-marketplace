package dgm.kit.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.testing.TestCertificates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.apache.kafka.common.config.types.Password;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTest {

    private static TestCertificates.Material material;

    @BeforeAll
    static void certificate() {
        material = TestCertificates.selfSigned("order-service");
    }

    @Test
    void parsesCertificateAndPkcs8Key() {
        assertEquals(1, Pem.certificates(material.certificatePem()).size());
        assertEquals("EC", Pem.privateKey(material.keyPem()).getAlgorithm());
    }

    @Test
    void rejectsGarbageAndOtherKeyFormats() {
        assertThrows(IllegalArgumentException.class, () -> Pem.certificates("not a certificate"));
        assertThrows(IllegalArgumentException.class, () -> Pem.privateKey(pemOf("EC PRIVATE KEY")));
        assertThrows(IllegalArgumentException.class, () -> Pem.privateKey(pemOf("PRIVATE KEY")));
    }

    /** Заведомо негодный блок PEM собирается на лету: в тексте исходников нет заголовка ключа, который искал бы сканер секретов. */
    private static String pemOf(String type) {
        return "-----" + "BEGIN " + type + "-----\nAAAA\n-----" + "END " + type + "-----\n";
    }

    @Test
    void buildsContextsFromSecretsDirectory(@TempDir Path dir) {
        TestCertificates.writeFiles(dir, "order-service", material);
        TlsMaterial tls = TlsMaterial.forService(dir, "order-service");
        SSLContext mutual = tls.mutualContext();
        assertNotNull(mutual.getSocketFactory());
        assertNotNull(tls.trustOnlyContext().getSocketFactory());
        assertTrue(mutual.getProtocol().startsWith("TLS"));
    }

    @Test
    void kafkaPropertiesUsePemAndHideKey(@TempDir Path dir) {
        TestCertificates.writeFiles(dir, "order-service", material);
        Map<String, Object> p = TlsMaterial.forService(dir, "order-service").kafkaProperties();
        assertEquals("SSL", p.get("security.protocol"));
        assertEquals("PEM", p.get("ssl.keystore.type"));
        assertEquals("PEM", p.get("ssl.truststore.type"));
        assertEquals("https", p.get("ssl.endpoint.identification.algorithm"));
        assertTrue(((String) p.get("ssl.truststore.certificates")).contains("BEGIN CERTIFICATE"));
        assertTrue(((String) p.get("ssl.keystore.certificate.chain")).contains("BEGIN CERTIFICATE"));
        Object key = p.get("ssl.keystore.key");
        assertTrue(key instanceof Password);
        assertFalse(p.toString().contains("PRIVATE KEY"), "ключ не печатается вместе с настройками");
        assertTrue(((Password) key).value().contains("BEGIN PRIVATE KEY"));
    }

    @Test
    void jdbcUrlVerifiesServerCertificate(@TempDir Path dir) {
        String url = TlsMaterial.forService(dir, "order-service").jdbcUrl("postgres", 5432, "order_db");
        assertTrue(url.startsWith("jdbc:postgresql://postgres:5432/order_db?"));
        assertTrue(url.contains("sslmode=verify-full"));
        assertTrue(url.contains("sslrootcert=" + dir.toAbsolutePath() + "/tls_ca.crt"));
        assertFalse(url.contains("password"));
    }

    @Test
    void missingFileGivesReadableError(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("tls_ca.crt"), material.certificatePem());
        TlsMaterial tls = TlsMaterial.forService(dir, "order-service");
        java.io.UncheckedIOException e = assertThrows(java.io.UncheckedIOException.class, tls::mutualContext);
        assertTrue(e.getMessage().contains("tls_order-service.crt"));
        assertFalse(e.getMessage().contains(dir.toString()), "полный путь в сообщение не попадает");
    }
}
