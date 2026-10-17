package dgm.kit.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Разбор multipart/form-data из байтов (RFC 7578): обычная форма браузера, двоичное содержимое, пустая часть и отклонения от формы. */
class MultipartFormTest {

    private static final String BOUNDARY = "----WebKitFormBoundary7MA4YWxkTrZu0gW";
    private static final String TYPE = "multipart/form-data; boundary=" + BOUNDARY;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] join(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static byte[] filePart(String fileName, String type, byte[] content) {
        return join(bytes("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + (type == null ? "" : "Content-Type: " + type + "\r\n") + "\r\n"), content, bytes("\r\n"));
    }

    private static byte[] form(byte[]... parts) {
        return join(join(parts), bytes("--" + BOUNDARY + "--\r\n"));
    }

    @Test
    void browserFormWithOneFileIsParsed() {
        byte[] content = {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, (byte) 0xFF, 13, 10, 45, 45};
        List<MultipartForm.Part> parts = MultipartForm.parse(TYPE, form(filePart("certificate.pdf", "application/pdf", content)));
        assertEquals(1, parts.size());
        MultipartForm.Part part = parts.get(0);
        assertEquals("file", part.name());
        assertEquals("certificate.pdf", part.fileName());
        assertEquals("application/pdf", part.declaredContentType());
        assertArrayEquals(content, part.content());
    }

    @Test
    void contentContainingBoundaryLookalikesIsKeptWhole() {
        byte[] content = bytes("начало\r\n--" + BOUNDARY.substring(0, 10) + " похоже, но не граница\r\n--\r\nконец");
        List<MultipartForm.Part> parts = MultipartForm.parse(TYPE, form(filePart("a.bin", null, content)));
        assertArrayEquals(content, parts.get(0).content());
        assertNull(parts.get(0).declaredContentType());
    }

    @Test
    void emptyFileIsAnEmptyPart() {
        List<MultipartForm.Part> parts = MultipartForm.parse(TYPE, form(filePart("empty.pdf", "application/pdf", new byte[0])));
        assertEquals(1, parts.size());
        assertEquals(0, parts.get(0).content().length);
    }

    @Test
    void severalPartsKeepTheirOrderAndPlainFieldsHaveNoFileName() {
        byte[] field = bytes("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nтекст\r\n");
        List<MultipartForm.Part> parts = MultipartForm.parse(TYPE, form(field, filePart("x.png", "image/png", new byte[] {1, 2, 3})));
        assertEquals(2, parts.size());
        assertEquals("note", parts.get(0).name());
        assertNull(parts.get(0).fileName());
        assertArrayEquals(bytes("текст"), parts.get(0).content());
        assertEquals("file", parts.get(1).name());
        assertArrayEquals(new byte[] {1, 2, 3}, parts.get(1).content());
    }

    @Test
    void quotedBoundaryEscapedFileNameAndUnicodeNameAreUnderstood() {
        String type = "Multipart/Form-Data; charset=utf-8; boundary=\"" + BOUNDARY + "\"";
        byte[] body = form(filePart("справка \\\"№1\\\".pdf", "application/pdf", new byte[] {7}));
        List<MultipartForm.Part> parts = MultipartForm.parse(type, body);
        assertEquals("справка \"№1\".pdf", parts.get(0).fileName());
    }

    @Test
    void textBeforeTheFirstBoundaryAndAfterTheLastOneIsIgnored() {
        byte[] body = join(bytes("preamble\r\n"), form(filePart("a.png", "image/png", new byte[] {9})), bytes("epilogue"));
        assertArrayEquals(new byte[] {9}, MultipartForm.parse(TYPE, body).get(0).content());
    }

    @Test
    void notMultipartIs415() {
        ProblemException e = assertThrows(ProblemException.class, () -> MultipartForm.parse("application/json", bytes("{}")));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, e.type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, assertThrows(ProblemException.class, () -> MultipartForm.parse(null, bytes("x"))).type());
    }

    @Test
    void brokenFormsAre400() {
        byte[] good = form(filePart("a.pdf", "application/pdf", new byte[] {1}));
        String text = new String(good, StandardCharsets.ISO_8859_1);
        List<byte[]> broken = List.of(
                bytes("совсем не форма"),
                new byte[0],
                text.replace("--" + BOUNDARY + "--\r\n", "").getBytes(StandardCharsets.ISO_8859_1),
                text.replace("form-data", "attachment").getBytes(StandardCharsets.ISO_8859_1),
                text.replace("; name=\"file\"", "").getBytes(StandardCharsets.ISO_8859_1),
                text.replace("\r\n\r\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1),
                bytes("--" + BOUNDARY + "garbage\r\n\r\n"));
        for (byte[] body : broken) {
            ProblemException e = assertThrows(ProblemException.class, () -> MultipartForm.parse(TYPE, body), new String(body, StandardCharsets.UTF_8));
            assertEquals(ProblemType.BAD_REQUEST, e.type());
        }
    }

    @Test
    void missingOrOversizedBoundaryIs400() {
        byte[] body = form(filePart("a.pdf", null, new byte[] {1}));
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> MultipartForm.parse("multipart/form-data", body)).type());
        assertEquals(ProblemType.BAD_REQUEST,
                assertThrows(ProblemException.class, () -> MultipartForm.parse("multipart/form-data; boundary=" + "b".repeat(71), body)).type());
    }

    @Test
    void tooManyPartsAre400() {
        byte[][] parts = new byte[MultipartForm.MAX_PARTS + 1][];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = filePart("f" + i + ".png", null, new byte[] {1});
        }
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> MultipartForm.parse(TYPE, form(parts))).type());
    }

    @Test
    void headersLongerThanTheLimitAreRefused() {
        byte[] body = form(filePart("a".repeat(MultipartForm.MAX_HEADER_BYTES) + ".pdf", null, new byte[] {1}));
        assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> MultipartForm.parse(TYPE, body)).type());
    }

    @Test
    void parameterReaderHandlesOrderQuotesAndAbsence() {
        assertEquals("a b", MultipartForm.parameter("form-data; name=\"x\"; filename=\"a b\"", "filename"));
        assertEquals("x", MultipartForm.parameter("form-data; filename=\"a;b\"; name=x", "name"));
        assertEquals("a;b", MultipartForm.parameter("form-data; filename=\"a;b\"; name=x", "filename"));
        assertNull(MultipartForm.parameter("form-data", "name"));
        assertNull(MultipartForm.parameter("form-data; name=\"x\"", "filename"));
    }
}
