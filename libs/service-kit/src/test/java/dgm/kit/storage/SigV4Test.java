package dgm.kit.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.secret.SecretValue;
import dgm.kit.storage.client.SigV4;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Подпись запросов S3 проверяется на примерах из документации AWS Signature Version 4 («Authenticating Requests», Amazon S3 API
 * Reference): ключи и значения в них учебные, подписи заранее известны. Совпадение с ними означает, что подпись примет любой
 * S3-совместимый сервер, в том числе RustFS.
 */
class SigV4Test {

    private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final Instant EXAMPLE_TIME = Instant.parse("2013-05-24T00:00:00Z");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final SigV4 signer = new SigV4(ACCESS_KEY, SecretValue.of(SECRET), "us-east-1");

    @Test
    void presignedUrlMatchesTheAwsExample() {
        String query = signer.presignedQuery("GET", "examplebucket.s3.amazonaws.com", "/test.txt", EXAMPLE_TIME, 86_400);
        assertEquals("X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404", query);
    }

    @Test
    void authorizationHeaderMatchesTheAwsGetExample() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "examplebucket.s3.amazonaws.com");
        headers.put("Range", "bytes=0-9");
        headers.put("x-amz-content-sha256", EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");
        String authorization = signer.authorization("GET", "/test.txt", headers, EMPTY_SHA256, EXAMPLE_TIME);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", authorization);
    }

    @Test
    void authorizationHeaderMatchesTheAwsPutExampleWithEncodedPath() {
        byte[] body = "Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8);
        String hash = SigV4.sha256Hex(body);
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", hash);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("x-amz-content-sha256", hash);
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        String authorization = signer.authorization("PUT", SigV4.encodePath("/test$file.text"), headers, hash, EXAMPLE_TIME);
        assertTrue(authorization.endsWith("Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd"), authorization);
        assertTrue(authorization.contains("SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class"), authorization);
    }

    @Test
    void pathEncodingKeepsSlashesAndEncodesTheRestOnce() {
        assertEquals("/seller-documents/0199e0a0-0000-7000-8000-000000000101/a.b_c~d", SigV4.encodePath("/seller-documents/0199e0a0-0000-7000-8000-000000000101/a.b_c~d"));
        assertEquals("/a%20b/%C3%BC%2B%24", SigV4.encodePath("/a b/ü+$"));
        assertEquals("%25", SigV4.encodePath("%"), "процент кодируется, а не пропускается как уже закодированный");
    }

    @Test
    void anotherSecretOrAnotherHostGivesAnotherSignature() {
        String base = signer.presignedQuery("GET", "localhost:8443", "/seller-documents/a", EXAMPLE_TIME, 300);
        String otherSecret = new SigV4(ACCESS_KEY, SecretValue.of(SECRET + "x"), "us-east-1")
                .presignedQuery("GET", "localhost:8443", "/seller-documents/a", EXAMPLE_TIME, 300);
        String otherHost = signer.presignedQuery("GET", "localhost:8444", "/seller-documents/a", EXAMPLE_TIME, 300);
        String otherPath = signer.presignedQuery("GET", "localhost:8443", "/seller-documents/b", EXAMPLE_TIME, 300);
        String otherTtl = signer.presignedQuery("GET", "localhost:8443", "/seller-documents/a", EXAMPLE_TIME, 301);
        for (String other : new String[] {otherSecret, otherHost, otherPath, otherTtl}) {
            assertFalse(base.equals(other));
            assertFalse(base.substring(base.indexOf("X-Amz-Signature")).equals(other.substring(other.indexOf("X-Amz-Signature"))));
        }
        assertTrue(base.contains("X-Amz-Expires=300"));
    }

    @Test
    void ttlOutsideSigV4LimitsIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> signer.presignedQuery("GET", "h", "/b/k", EXAMPLE_TIME, 0));
        assertThrows(IllegalArgumentException.class, () -> signer.presignedQuery("GET", "h", "/b/k", EXAMPLE_TIME, SigV4.MAX_EXPIRES_SECONDS + 1));
        signer.presignedQuery("GET", "h", "/b/k", EXAMPLE_TIME, SigV4.MAX_EXPIRES_SECONDS);
    }

    @Test
    void secretNeverAppearsInTheOutput() {
        String query = signer.presignedQuery("GET", "localhost", "/b/k", EXAMPLE_TIME, 300);
        assertFalse(query.contains(SECRET));
        assertFalse(query.contains("EXAMPLEKEY"));
        assertEquals("SecretValue[****]", SecretValue.of(SECRET).toString());
    }
}
