package dgm.catalog.sellers.controller;

import dgm.catalog.sellers.domain.FieldViolation;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.service.SellerDocumentService;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.web.MultipartForm;
import dgm.kit.web.RequestBodies;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Документы заявки: {@code uploadSellerDocument}, {@code deleteSellerDocument}, {@code getSellerDocumentLink} (OP-10). Форма
 * {@code multipart/form-data} разбирается вручную ({@link MultipartForm}): тело уже прочитано фильтром идемпотентности, а встроенный
 * разбор Spring выключен (Р-61).
 */
@RestController
public class SellerDocumentController {

    private static final String FILE_PART = "file";
    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final SellerDocumentService service;

    public SellerDocumentController(SellerDocumentService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/seller-applications/current/documents")
    public ResponseEntity<String> upload(HttpServletRequest request) throws IOException {
        UUID userId = SellerApplicationController.userId(request);
        byte[] body = RequestBodies.read(request, RequestBodies.limitFor(request, RequestBodies.DEFAULT_LIMIT));
        MultipartForm.Part file = filePart(MultipartForm.parse(request.getContentType(), body));
        SellerDocument document = service.upload(userId, file.fileName(), file.content());
        return ResponseEntity.status(HttpStatus.CREATED).contentType(SellerApplicationController.JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.LOCATION, SellerApplicationController.CURRENT + "/documents/" + document.id())
                .body(Json.write(SellerViews.document(document)));
    }

    @DeleteMapping("/api/v1/seller-applications/current/documents/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable("documentId") String documentId, HttpServletRequest request) {
        service.delete(SellerApplicationController.userId(request), documentId(documentId));
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GetMapping("/api/v1/seller-applications/current/documents/{documentId}/link")
    public ResponseEntity<String> link(@PathVariable("documentId") String documentId, HttpServletRequest request) {
        var link = service.link(SellerApplicationController.userId(request), documentId(documentId));
        return ResponseEntity.ok().contentType(SellerApplicationController.JSON).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Json.write(SellerViews.link(link)));
    }

    /** Ровно одна часть {@code file} с именем файла; лишние части и поля формы не принимаются. */
    static MultipartForm.Part filePart(List<MultipartForm.Part> parts) {
        List<FieldViolation> errors = new ArrayList<>();
        MultipartForm.Part file = null;
        for (MultipartForm.Part part : parts) {
            if (!FILE_PART.equals(part.name())) {
                errors.add(new FieldViolation("/" + part.name(), "unknown_value", "Лишняя часть формы: допустима только file."));
            } else if (part.fileName() == null) {
                errors.add(new FieldViolation("/file", "invalid_format", "Нужен файл, а не текстовое поле."));
            } else if (file != null) {
                errors.add(new FieldViolation("/file", "not_unique", "Часть file должна быть одна."));
            } else {
                file = part;
            }
        }
        if (file == null && errors.isEmpty()) {
            errors.add(new FieldViolation("/file", "required", "В форме нет части file."));
        }
        if (!errors.isEmpty()) {
            throw SellerProfile.validation(errors);
        }
        return file;
    }

    private static UUID documentId(String value) {
        if (!ID.matcher(value).matches()) {
            throw new ProblemException(ProblemType.BAD_REQUEST, "Идентификатор документа должен быть UUID в нижнем регистре.");
        }
        return UUID.fromString(value);
    }
}
