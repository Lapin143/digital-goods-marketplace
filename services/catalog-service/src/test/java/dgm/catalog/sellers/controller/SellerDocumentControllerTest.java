package dgm.catalog.sellers.controller;

import static dgm.catalog.sellers.controller.ControllerFixture.body;
import static dgm.catalog.sellers.controller.ControllerFixture.request;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** Операции с документами заявки по схемам SellerDocument и DocumentLink: форма multipart, ответы и ошибки. */
@Tag("TC-205")
@Tag("TC-206")
@Tag("TC-207")
class SellerDocumentControllerTest {

    private static final String BOUNDARY = "----dgmBoundary7MA4YWxk";
    private static final String UPLOAD = "/api/v1/seller-applications/current/documents";

    private ControllerFixture fx;
    private SellerDocumentController controller;

    @BeforeEach
    void setUp() {
        fx = new ControllerFixture();
        controller = fx.documentController;
        fx.sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.DRAFT, SellerForm.empty(), ControllerFixture.T0));
    }

    private static byte[] pdf() {
        byte[] content = new byte[300];
        System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, content, 0, 9);
        return content;
    }

    private static byte[] join(byte[]... chunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static byte[] part(String name, String fileName, byte[] content) {
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\"" + (fileName == null ? "" : "; filename=\"" + fileName + "\"");
        return join(("--" + BOUNDARY + "\r\n" + disposition + "\r\n\r\n").getBytes(StandardCharsets.UTF_8), content, "\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest form(byte[]... parts) {
        MockHttpServletRequest request = request("POST", UPLOAD);
        request.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        request.setContent(join(join(parts), ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private static ProblemException problem(Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    private static List<String> pointers(ProblemException e) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) e.extensions().get("errors");
        return errors.stream().map(m -> (String) m.get("pointer") + ":" + m.get("code")).toList();
    }

    private Map<String, Object> upload(String fileName) throws IOException {
        ResponseEntity<String> response = controller.upload(form(part("file", fileName, pdf())));
        assertEquals(201, response.getStatusCode().value());
        return body(response);
    }

    // ---------------------------------------------------------------- uploadSellerDocument

    @Test
    void uploadAnswers201WithTheDocumentAndALocation() throws IOException {
        ResponseEntity<String> response = controller.upload(form(part("file", "certificate.pdf", pdf())));
        assertEquals(201, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = body(response);
        assertEquals(Set.of("documentId", "fileName", "contentType", "sizeBytes", "uploadedAt"), body.keySet());
        assertEquals("certificate.pdf", body.get("fileName"));
        assertEquals("application/pdf", body.get("contentType"));
        assertEquals(300, ((Number) body.get("sizeBytes")).intValue());
        assertEquals("2026-10-03T12:00:00.000Z", body.get("uploadedAt"));
        assertEquals("/api/v1/seller-applications/current/documents/" + body.get("documentId"), response.getHeaders().getFirst("Location"));
        assertEquals(1, fx.storage.objects().size());
    }

    @Test
    void fileContentArrivesInTheStorageByteForByte() throws IOException {
        byte[] content = join(pdf(), "\r\n--".getBytes(StandardCharsets.UTF_8), new byte[] {0, (byte) 0xFF, 13, 10});
        controller.upload(form(part("file", "a.pdf", content)));
        assertArrayEquals(content, fx.storage.objects().values().iterator().next().content());
    }

    @Test
    void typeIsTakenFromTheContentNotFromTheNameOrFromTheDeclaredType() throws IOException {
        Map<String, Object> body = upload("photo.png");
        assertEquals("application/pdf", body.get("contentType"));
    }

    @Test
    void textWithAPdfNameIs415() {
        MockHttpServletRequest request = form(part("file", "certificate.pdf", "обычный текст".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.upload(request)).type());
        assertTrue(fx.storage.objects().isEmpty());
    }

    @Test
    void notAMultipartFormIs415() {
        MockHttpServletRequest request = request("POST", UPLOAD);
        request.setContentType("application/pdf");
        request.setContent(pdf());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.upload(request)).type());
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, problem(() -> controller.upload(request("POST", UPLOAD))).type());
    }

    @Test
    void brokenFormIs400() {
        MockHttpServletRequest request = request("POST", UPLOAD);
        request.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        request.setContent("совсем не форма".getBytes(StandardCharsets.UTF_8));
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> controller.upload(request)).type());
    }

    @Test
    void formWithoutThePartFileIs422() {
        assertEquals(List.of("/file:required"), pointers(problem(() -> controller.upload(form()))));
    }

    @Test
    void wrongPartNameIs422() {
        assertEquals(List.of("/document:unknown_value"), pointers(problem(() -> controller.upload(form(part("document", "a.pdf", pdf()))))));
    }

    @Test
    void textFieldNamedFileIsNotAFile() {
        assertEquals(List.of("/file:invalid_format"), pointers(problem(() -> controller.upload(form(part("file", null, pdf()))))));
    }

    @Test
    void twoFilesAreRefused() {
        MockHttpServletRequest request = form(part("file", "a.pdf", pdf()), part("file", "b.pdf", pdf()));
        assertEquals(List.of("/file:not_unique"), pointers(problem(() -> controller.upload(request))));
        assertTrue(fx.storage.objects().isEmpty());
    }

    @Test
    void extraFieldsAreRefusedTogetherWithTheFile() {
        MockHttpServletRequest request = form(part("note", null, "x".getBytes(StandardCharsets.UTF_8)), part("file", "a.pdf", pdf()));
        assertEquals(List.of("/note:unknown_value"), pointers(problem(() -> controller.upload(request))));
    }

    @Test
    void emptyFileIs422() {
        assertEquals(ProblemType.VALIDATION_FAILED, problem(() -> controller.upload(form(part("file", "a.pdf", new byte[0])))).type());
    }

    @Test
    void fileOverTwoMegabytesIs413BeforeAnythingIsStored() {
        byte[] big = new byte[2_097_153];
        System.arraycopy(pdf(), 0, big, 0, 9);
        ProblemException e = problem(() -> controller.upload(form(part("file", "big.pdf", big))));
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, e.type());
        assertTrue(e.getMessage().contains("2 МБ"));
        assertTrue(fx.storage.objects().isEmpty());
    }

    @Test
    void fileOfExactlyTwoMegabytesIsAccepted() throws IOException {
        byte[] exact = new byte[2_097_152];
        System.arraycopy(pdf(), 0, exact, 0, 9);
        ResponseEntity<String> response = controller.upload(form(part("file", "exact.pdf", exact)));
        assertEquals(201, response.getStatusCode().value());
        assertEquals(2_097_152, ((Number) body(response).get("sizeBytes")).intValue());
    }

    @Test
    void uploadToAnApplicationOnReviewIs409() {
        fx.sellers.put(FakeSellers.profile(FakeSellers.USER, SellerStatus.ON_REVIEW, FakeSellers.completeForm(), ControllerFixture.T0));
        ProblemException e = problem(() -> controller.upload(form(part("file", "a.pdf", pdf()))));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals("on_review", e.extensions().get("currentStatus"));
    }

    @Test
    void uploadWithoutAnApplicationIs404() {
        MockHttpServletRequest request = form(part("file", "a.pdf", pdf()));
        request.setAttribute(dgm.kit.security.JwtFilter.USER_ATTRIBUTE, new dgm.kit.security.AuthenticatedUser(FakeSellers.OTHER_USER.toString(),
                Set.of("seller.apply"), Set.of("buyer"), List.of("pwd"), true));
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.upload(request)).type());
    }

    @Test
    void eleventhFileIs422() throws IOException {
        for (int i = 0; i < 10; i++) {
            upload("d" + i + ".pdf");
        }
        assertEquals(List.of("/documents:out_of_range"), pointers(problem(() -> controller.upload(form(part("file", "d10.pdf", pdf()))))));
        assertEquals(10, fx.storage.objects().size());
    }

    // ---------------------------------------------------------------- deleteSellerDocument

    @Test
    void deleteAnswers204AndTheSecondDeleteIs404() throws IOException {
        String id = (String) upload("a.pdf").get("documentId");
        ResponseEntity<Void> response = controller.delete(id, request("DELETE", UPLOAD + "/" + id));
        assertEquals(204, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertTrue(fx.storage.objects().isEmpty());
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.delete(id, request("DELETE", UPLOAD + "/" + id))).type());
    }

    @Test
    void deleteWithAMalformedIdentifierIs400() {
        for (String bad : new String[] {"123", "not-a-uuid", "0199E0A0-0000-7000-8000-000000000901", "../x"}) {
            assertEquals(ProblemType.BAD_REQUEST, problem(() -> controller.delete(bad, request("DELETE", UPLOAD + "/x"))).type(), bad);
        }
    }

    @Test
    void deleteOfAnUnknownDocumentIs404() {
        String id = UUID.randomUUID().toString();
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.delete(id, request("DELETE", UPLOAD + "/" + id))).type());
    }

    // ---------------------------------------------------------------- getSellerDocumentLink

    @Test
    void linkAnswersWithTheUrlAndTheMomentItExpires() throws IOException {
        String id = (String) upload("a.pdf").get("documentId");
        ResponseEntity<String> response = controller.link(id, request("GET", UPLOAD + "/" + id + "/link"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = body(response);
        assertEquals(Set.of("url", "expiresAt"), body.keySet());
        assertTrue(((String) body.get("url")).startsWith("https://localhost:8443/files/seller-documents/"));
        assertEquals("2026-10-03T12:05:00.000Z", body.get("expiresAt"));
    }

    @Test
    void linkOfAnotherUsersDocumentIs404() throws IOException {
        String id = (String) upload("a.pdf").get("documentId");
        fx.sellers.put(FakeSellers.profile(FakeSellers.OTHER_USER, SellerStatus.DRAFT, SellerForm.empty(), ControllerFixture.T0));
        MockHttpServletRequest other = request("GET", UPLOAD + "/" + id + "/link", FakeSellers.OTHER_USER);
        assertEquals(ProblemType.NOT_FOUND, problem(() -> controller.link(id, other)).type());
    }

    @Test
    void linkWithAMalformedIdentifierIs400() {
        assertEquals(ProblemType.BAD_REQUEST, problem(() -> controller.link("x", request("GET", UPLOAD + "/x/link"))).type());
    }
}
