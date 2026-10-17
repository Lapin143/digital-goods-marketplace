package dgm.catalog.sellers.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Документ заявки. Сам файл лежит в хранилище, в базе только ключ объекта ({@code objectKey}).
 *
 * @param objectKey ключ в бакете: {@code <sellerId>/<documentId>}, текст пользователя в него не входит
 * @param fileName  имя файла для показа модератору (без пути)
 */
public record SellerDocument(UUID id, UUID sellerId, String objectKey, String fileName, DocumentType type, long sizeBytes, Instant uploadedAt) {

    /** Не больше файлов в заявке (FT-2.0). */
    public static final int MAX_PER_APPLICATION = 10;
    /** Размер файла: 2 МБ, то есть 2 097 152 байта, на шлюзе, в сервисе и в ограничении таблицы (Р-14). */
    public static final long MAX_SIZE_BYTES = 2L * 1024 * 1024;
    public static final int MAX_FILE_NAME = 255;

    public static String objectKey(UUID sellerId, UUID documentId) {
        return sellerId + "/" + documentId;
    }
}
