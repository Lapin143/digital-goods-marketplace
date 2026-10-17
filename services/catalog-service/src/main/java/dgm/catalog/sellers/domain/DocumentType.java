package dgm.catalog.sellers.domain;

import java.util.Optional;

/**
 * Тип документа заявки. Определяется по первым байтам самого файла, а не по имени и не по заявленному {@code Content-Type}:
 * текстовый файл с расширением {@code .pdf} PDF не становится (угроза T-36, ADR-024).
 */
public enum DocumentType {
    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png");

    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final String contentType;

    DocumentType(String contentType) {
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }

    /** Тип по содержимому; пусто, если файл не PDF, не JPEG и не PNG. */
    public static Optional<DocumentType> detect(byte[] content) {
        if (startsWith(content, PDF_MAGIC)) {
            return Optional.of(PDF);
        }
        if (startsWith(content, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        return Optional.empty();
    }

    public static Optional<DocumentType> fromContentType(String contentType) {
        for (DocumentType type : values()) {
            if (type.contentType.equals(contentType)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content == null || content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
