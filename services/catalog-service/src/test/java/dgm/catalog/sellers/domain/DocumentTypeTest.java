package dgm.catalog.sellers.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Тип документа определяется по первым байтам файла, а не по имени и не по заявленному типу. */
class DocumentTypeTest {

    private static byte[] withTail(byte[] head) {
        byte[] content = new byte[head.length + 20];
        System.arraycopy(head, 0, content, 0, head.length);
        return content;
    }

    @Test
    void pdfJpegAndPngAreRecognisedByContent() {
        assertEquals(Optional.of(DocumentType.PDF), DocumentType.detect(withTail("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(Optional.of(DocumentType.JPEG), DocumentType.detect(withTail(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})));
        assertEquals(Optional.of(DocumentType.PNG),
                DocumentType.detect(withTail(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})));
    }

    @Test
    void textRenamedToPdfIsNotAPdf() {
        assertTrue(DocumentType.detect("Это просто текст, а не документ".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void htmlAndSvgAreRefused() {
        assertTrue(DocumentType.detect("<!doctype html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(DocumentType.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test
    void headerMustStartAtTheFirstByte() {
        assertTrue(DocumentType.detect(" %PDF-1.7".getBytes(StandardCharsets.US_ASCII)).isEmpty());
        assertTrue(DocumentType.detect("%PDF".getBytes(StandardCharsets.US_ASCII)).isEmpty(), "без дефиса это не заголовок PDF");
        assertTrue(DocumentType.detect(new byte[] {(byte) 0xFF, (byte) 0xD8}).isEmpty(), "короче сигнатуры JPEG");
        assertTrue(DocumentType.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G'}).isEmpty(), "короче сигнатуры PNG");
    }

    @Test
    void emptyAndMissingContentIsNotADocument() {
        assertTrue(DocumentType.detect(new byte[0]).isEmpty());
        assertTrue(DocumentType.detect(null).isEmpty());
    }

    @Test
    void contentTypesMatchTheDatabaseConstraint() {
        assertEquals("application/pdf", DocumentType.PDF.contentType());
        assertEquals("image/jpeg", DocumentType.JPEG.contentType());
        assertEquals("image/png", DocumentType.PNG.contentType());
        for (DocumentType type : DocumentType.values()) {
            assertEquals(Optional.of(type), DocumentType.fromContentType(type.contentType()));
        }
        assertTrue(DocumentType.fromContentType("text/html").isEmpty());
    }
}
