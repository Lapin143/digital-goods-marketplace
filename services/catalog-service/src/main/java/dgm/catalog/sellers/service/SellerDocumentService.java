package dgm.catalog.sellers.service;

import dgm.catalog.sellers.domain.DocumentType;
import dgm.catalog.sellers.domain.FieldViolation;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.repository.SellerRepository;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.storage.ObjectStorage;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Документы заявки (OP-10): загрузка, удаление, ссылка на скачивание. Файл лежит в закрытом бакете, в базе только ключ объекта.
 * Тип файла определяется по содержимому. Ссылка это подпись SigV4 на 5 минут, считается без вызова хранилища; её открывает шлюз
 * по маршруту {@code /files/**}. Антивирусной проверки нет (модель угроз, T-36): файлы отдаются как вложения под политикой CSP.
 */
@Component
public final class SellerDocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(SellerDocumentService.class);

    private final SellerRepository repository;
    private final SellerTransaction transaction;
    private final ObjectStorage storage;
    private final Clock clock;
    private final SellerSettings settings;

    public SellerDocumentService(SellerRepository repository, SellerTransaction transaction, ObjectStorage storage, Clock clock,
                                 SellerSettings settings) {
        this.repository = repository;
        this.transaction = transaction;
        this.storage = storage;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * Сохраняет документ. Проверки по порядку: файл не пуст (422), не больше 2 МБ (413), PDF, JPEG или PNG по содержимому (415); затем
     * профиль есть (404), статус допускает правку (409), документов меньше десяти (422 {@code out_of_range}).
     *
     * @param fileName имя из формы, без доверия: путь и управляющие знаки отбрасываются
     */
    public SellerDocument upload(UUID userId, String fileName, byte[] content) {
        if (content.length == 0) {
            throw SellerProfile.validation(List.of(new FieldViolation("/file", "required", "Файл пуст.")));
        }
        if (content.length > SellerDocument.MAX_SIZE_BYTES) {
            throw new ProblemException(ProblemType.PAYLOAD_TOO_LARGE, "Файл больше допустимых 2 МБ.");
        }
        DocumentType type = DocumentType.detect(content).orElseThrow(() -> new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE,
                "Допустимы PDF, JPEG и PNG: тип определяется по содержимому файла, а не по его имени."));
        String name = cleanFileName(fileName, type);

        return transaction.execute(() -> {
            SellerProfile profile = repository.lockByUser(userId).orElseThrow(SellerApplicationService::notFound);
            profile.requireEditable();
            if (repository.countDocuments(profile.id()) >= SellerDocument.MAX_PER_APPLICATION) {
                throw SellerProfile.validation(List.of(new FieldViolation("/documents", "out_of_range",
                        "В заявке не больше " + SellerDocument.MAX_PER_APPLICATION + " документов: удалите лишний.")));
            }
            UUID id = Uuids.v7(clock);
            String key = SellerDocument.objectKey(profile.id(), id);
            storage.put(key, content, type.contentType());
            SellerDocument document = new SellerDocument(id, profile.id(), key, name, type, content.length, clock.instant());
            try {
                repository.insertDocument(document);
            } catch (RuntimeException e) {
                discard(key);
                throw e;
            }
            return document;
        });
    }

    /** Удаляет документ из заявки в статусах, где правка допустима; повтор после успеха это 404. Объект удаляется после записи в базе. */
    public void delete(UUID userId, UUID documentId) {
        String key = transaction.execute(() -> {
            SellerProfile profile = repository.lockByUser(userId).orElseThrow(SellerApplicationService::notFound);
            profile.requireEditable();
            SellerDocument document = repository.findDocument(profile.id(), documentId).orElseThrow(SellerDocumentService::documentNotFound);
            repository.deleteDocument(profile.id(), documentId);
            return document.objectKey();
        });
        discard(key);
    }

    /** Подписанная ссылка на документ своей заявки в любом статусе профиля. */
    public ObjectStorage.PresignedLink link(UUID userId, UUID documentId) {
        SellerDocument document = repository.findUserDocument(userId, documentId).orElseThrow(SellerDocumentService::documentNotFound);
        return storage.presignGet(document.objectKey(), settings.documentLinkTtl());
    }

    /**
     * Имя файла для показа: последняя часть пути, без управляющих знаков, не длиннее 255 знаков. Пустое имя заменяется на
     * {@code document} с расширением по типу.
     */
    static String cleanFileName(String raw, DocumentType type) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        StringBuilder clean = new StringBuilder();
        name.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(clean::appendCodePoint);
        String result = clean.toString().strip();
        if (result.isEmpty() || result.equals(".") || result.equals("..")) {
            return "document" + extension(type);
        }
        if (result.codePointCount(0, result.length()) > SellerDocument.MAX_FILE_NAME) {
            throw SellerProfile.validation(List.of(new FieldViolation("/file", "too_long",
                    "Имя файла не длиннее " + SellerDocument.MAX_FILE_NAME + " знаков.")));
        }
        return result;
    }

    private static String extension(DocumentType type) {
        return switch (type) {
            case PDF -> ".pdf";
            case JPEG -> ".jpg";
            case PNG -> ".png";
        };
    }

    /** Лишний объект не должен ломать ответ: сбой удаления уходит в журнал, объект без записи в базе недоступен никому. */
    private void discard(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            LOG.warn("Объект {} не удалён из хранилища, остался без записи в базе: {}", key, e.getMessage());
        }
    }

    private static ProblemException documentNotFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Документ не найден.");
    }
}
