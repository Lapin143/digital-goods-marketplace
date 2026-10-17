package dgm.catalog.sellers.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.sellers.FakeSellers;
import dgm.catalog.sellers.FakeStorage;
import dgm.catalog.sellers.FakeTransaction;
import dgm.catalog.sellers.domain.DocumentType;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.storage.ObjectStorage;
import dgm.kit.time.ManualClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Документы заявки: тип по содержимому, размер, предел количества, порядок записи в базу и хранилище, ссылка на 5 минут. */
class SellerDocumentServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final UUID USER = FakeSellers.USER;

    private ManualClock clock;
    private FakeSellers sellers;
    private FakeStorage storage;
    private SellerDocumentService service;
    private SellerProfile profile;

    private static byte[] pdf(int size) {
        byte[] content = new byte[size];
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, content, 0, Math.min(head.length, size));
        return content;
    }

    private static byte[] jpeg() {
        byte[] content = new byte[64];
        content[0] = (byte) 0xFF;
        content[1] = (byte) 0xD8;
        content[2] = (byte) 0xFF;
        return content;
    }

    private static byte[] png() {
        byte[] content = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, content, 0, 8);
        return content;
    }

    @BeforeEach
    void setUp() {
        clock = new ManualClock(T0);
        sellers = new FakeSellers();
        storage = new FakeStorage(clock);
        service = new SellerDocumentService(sellers, new FakeTransaction(), storage, clock, SellerSettings.defaults());
        profile = FakeSellers.profile(USER, SellerStatus.DRAFT, SellerForm.empty(), T0);
        sellers.put(profile);
    }

    private ProblemException failure(Runnable action) {
        return assertThrows(ProblemException.class, action::run);
    }

    // ---------------------------------------------------------------- загрузка

    @Test
    void pdfJpegAndPngAreStoredWithTheTypeFoundInTheContent() {
        SellerDocument a = service.upload(USER, "certificate.pdf", pdf(100));
        SellerDocument b = service.upload(USER, "photo.jpg", jpeg());
        SellerDocument c = service.upload(USER, "scan.png", png());
        assertEquals(List.of(DocumentType.PDF, DocumentType.JPEG, DocumentType.PNG), List.of(a.type(), b.type(), c.type()));
        assertEquals(3, sellers.countDocuments(profile.id()));
        assertEquals(3, storage.objects().size());
        assertEquals("application/pdf", storage.objects().get(a.objectKey()).contentType());
        assertEquals("image/jpeg", storage.objects().get(b.objectKey()).contentType());
        assertEquals("image/png", storage.objects().get(c.objectKey()).contentType());
    }

    @Test
    void theRecordHoldsOnlyTheKeyAndTheFileLiesInTheStorageUnchanged() {
        byte[] content = pdf(1234);
        SellerDocument document = service.upload(USER, "certificate.pdf", content);
        assertEquals(profile.id() + "/" + document.id(), document.objectKey());
        assertEquals(1234, document.sizeBytes());
        assertEquals(profile.id(), document.sellerId());
        assertEquals(7, document.id().version());
        assertEquals(T0, document.uploadedAt());
        assertArrayEquals(content, storage.objects().get(document.objectKey()).content());
        assertEquals(document, sellers.findDocument(profile.id(), document.id()).orElseThrow());
    }

    @Test
    void textRenamedToPdfIsRefusedWithMediaTypeErrorAndNothingIsStored() {
        ProblemException e = failure(() -> service.upload(USER, "certificate.pdf", "обычный текст".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, e.type());
        assertTrue(storage.objects().isEmpty());
        assertEquals(0, sellers.countDocuments(profile.id()));
    }

    @Test
    void htmlWithAPictureNameIsRefused() {
        ProblemException e = failure(() -> service.upload(USER, "scan.png", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ProblemType.UNSUPPORTED_MEDIA_TYPE, e.type());
    }

    @Test
    void emptyFileIs422() {
        ProblemException e = failure(() -> service.upload(USER, "empty.pdf", new byte[0]));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertTrue(storage.objects().isEmpty());
    }

    @Test
    void twoMegabytesExactlyAreAcceptedAndOneByteMoreIsRefused() {
        assertEquals(2_097_152L, SellerDocument.MAX_SIZE_BYTES);
        SellerDocument exact = service.upload(USER, "big.pdf", pdf(2_097_152));
        assertEquals(2_097_152L, exact.sizeBytes());
        ProblemException e = failure(() -> service.upload(USER, "bigger.pdf", pdf(2_097_153)));
        assertEquals(ProblemType.PAYLOAD_TOO_LARGE, e.type());
        assertEquals(1, storage.objects().size());
        assertEquals(1, sellers.countDocuments(profile.id()));
    }

    @Test
    void theEleventhDocumentIsRefusedAndNothingIsWritten() {
        for (int i = 0; i < SellerDocument.MAX_PER_APPLICATION; i++) {
            service.upload(USER, "d" + i + ".pdf", pdf(50));
        }
        assertEquals(10, storage.objects().size());
        ProblemException e = failure(() -> service.upload(USER, "d10.pdf", pdf(50)));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        @SuppressWarnings("unchecked")
        Map<String, Object> error = ((List<Map<String, Object>>) e.extensions().get("errors")).get(0);
        assertEquals("/documents", error.get("pointer"));
        assertEquals("out_of_range", error.get("code"));
        assertEquals(10, storage.objects().size());
        assertEquals(10, sellers.countDocuments(profile.id()));
    }

    @Test
    void deletingOneMakesRoomForAnother() {
        SellerDocument first = null;
        for (int i = 0; i < SellerDocument.MAX_PER_APPLICATION; i++) {
            SellerDocument d = service.upload(USER, "d" + i + ".pdf", pdf(50));
            first = first == null ? d : first;
        }
        service.delete(USER, first.id());
        assertNotNull(service.upload(USER, "new.pdf", pdf(50)));
    }

    @ParameterizedTest
    @EnumSource(value = SellerStatus.class, names = {"ON_REVIEW", "APPROVED", "BLOCKED"})
    void documentsAreNotAcceptedWhileTheApplicationIsWithTheModeratorOrDecided(SellerStatus status) {
        sellers.put(FakeSellers.profile(USER, status, FakeSellers.completeForm(), T0));
        ProblemException e = failure(() -> service.upload(USER, "a.pdf", pdf(50)));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(status.code(), e.extensions().get("currentStatus"));
        assertTrue(storage.objects().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = SellerStatus.class, names = {"DRAFT", "RETURNED", "REJECTED"})
    void documentsAreAcceptedInDraftReturnedAndRejected(SellerStatus status) {
        sellers.put(FakeSellers.profile(USER, status, FakeSellers.completeForm(), T0));
        assertNotNull(service.upload(USER, "a.pdf", pdf(50)));
    }

    @Test
    void uploadWithoutAProfileIsNotFound() {
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.upload(FakeSellers.OTHER_USER, "a.pdf", pdf(50))).type());
        assertTrue(storage.objects().isEmpty());
    }

    @Test
    void unavailableStorageGives503AndLeavesNoRecord() {
        storage.failPut = true;
        ProblemException e = failure(() -> service.upload(USER, "a.pdf", pdf(50)));
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, e.type());
        assertEquals("5", e.headers().get("Retry-After"));
        assertEquals(0, sellers.countDocuments(profile.id()));
    }

    @Test
    void databaseFailureAfterTheFileWasWrittenRemovesTheFile() {
        sellers.failDocumentInsert = true;
        assertThrows(IllegalStateException.class, () -> service.upload(USER, "a.pdf", pdf(50)));
        assertTrue(storage.objects().isEmpty(), "объект без записи в базе никому не нужен");
    }

    @Test
    void failureToRemoveAnOrphanDoesNotHideTheOriginalError() {
        sellers.failDocumentInsert = true;
        storage.failDelete = true;
        assertThrows(IllegalStateException.class, () -> service.upload(USER, "a.pdf", pdf(50)));
    }

    // ---------------------------------------------------------------- имя файла

    @Test
    void fileNameLosesItsPathAndControlCharacters() {
        assertEquals("certificate.pdf", service.upload(USER, "C:\\Users\\me\\certificate.pdf", pdf(50)).fileName());
        assertEquals("certificate.pdf", service.upload(USER, "../../etc/certificate.pdf", pdf(50)).fileName());
        assertEquals("a b.pdf", service.upload(USER, "a\u0000 b\r\n.pdf", pdf(50)).fileName());
        assertEquals("справка №1.pdf", service.upload(USER, "  справка №1.pdf  ", pdf(50)).fileName());
    }

    @Test
    void emptyNameIsReplacedByTheTypeExtension() {
        assertEquals("document.pdf", SellerDocumentService.cleanFileName(null, DocumentType.PDF));
        assertEquals("document.jpg", SellerDocumentService.cleanFileName("", DocumentType.JPEG));
        assertEquals("document.png", SellerDocumentService.cleanFileName("dir/", DocumentType.PNG));
        assertEquals("document.pdf", SellerDocumentService.cleanFileName("..", DocumentType.PDF));
        assertEquals("document.pdf", SellerDocumentService.cleanFileName("  \t ", DocumentType.PDF));
    }

    @Test
    void nameLongerThanTwoHundredFiftyFiveCharactersIs422() {
        assertEquals("а".repeat(255), SellerDocumentService.cleanFileName("а".repeat(255), DocumentType.PDF));
        ProblemException e = assertThrows(ProblemException.class, () -> SellerDocumentService.cleanFileName("а".repeat(256), DocumentType.PDF));
        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
    }

    @Test
    void theFileNameIsNeverPartOfTheObjectKey() {
        SellerDocument document = service.upload(USER, "../../../secret.pdf", pdf(50));
        assertFalse(document.objectKey().contains("secret"));
        assertFalse(document.objectKey().contains(".."));
    }

    // ---------------------------------------------------------------- удаление

    @Test
    void deleteRemovesTheRecordAndTheObjectAndRepeatIsNotFound() {
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        service.delete(USER, document.id());
        assertEquals(0, sellers.countDocuments(profile.id()));
        assertTrue(storage.objects().isEmpty());
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.delete(USER, document.id())).type());
    }

    @Test
    void deleteSurvivesAStorageFailureBecauseTheRecordIsAlreadyGone() {
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        storage.failDelete = true;
        service.delete(USER, document.id());
        assertEquals(0, sellers.countDocuments(profile.id()));
    }

    @Test
    void deleteIsRefusedWhileTheApplicationIsWithTheModerator() {
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        sellers.put(FakeSellers.profile(USER, SellerStatus.ON_REVIEW, FakeSellers.completeForm(), T0));
        ProblemException e = failure(() -> service.delete(USER, document.id()));
        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(1, sellers.countDocuments(profile.id()));
        assertEquals(1, storage.objects().size());
    }

    @Test
    void deleteOfAnUnknownOrForeignDocumentIsNotFound() {
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        sellers.put(FakeSellers.profile(FakeSellers.OTHER_USER, SellerStatus.DRAFT, SellerForm.empty(), T0));
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.delete(FakeSellers.OTHER_USER, document.id())).type());
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.delete(USER, UUID.randomUUID())).type());
        assertEquals(1, sellers.countDocuments(profile.id()));
    }

    // ---------------------------------------------------------------- ссылка

    @Test
    void linkLivesFiveMinutes() {
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        clock.advance(Duration.ofMinutes(2));
        ObjectStorage.PresignedLink link = service.link(USER, document.id());
        assertEquals(Duration.ofMinutes(5), storage.lastTtl);
        assertEquals(clock.instant().plusSeconds(300), link.expiresAt());
        assertTrue(link.url().toString().contains(document.objectKey()));
    }

    @Test
    void linkTtlComesFromTheParameter() {
        service = new SellerDocumentService(sellers, new FakeTransaction(), storage, clock,
                new SellerSettings(Duration.ofHours(24), Duration.ofDays(3), Duration.ofSeconds(60)));
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        service.link(USER, document.id());
        assertEquals(Duration.ofSeconds(60), storage.lastTtl);
    }

    @ParameterizedTest
    @EnumSource(SellerStatus.class)
    void ownerGetsALinkInAnyStatus(SellerStatus status) {
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        sellers.put(FakeSellers.profile(USER, status, FakeSellers.completeForm(), T0));
        assertNotNull(service.link(USER, document.id()));
    }

    @Test
    void anotherUserCannotGetALinkAndLearnsNothingAboutTheDocument() {
        SellerDocument document = service.upload(USER, "a.pdf", pdf(50));
        sellers.put(FakeSellers.profile(FakeSellers.OTHER_USER, SellerStatus.DRAFT, SellerForm.empty(), T0));
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.link(FakeSellers.OTHER_USER, document.id())).type());
        assertEquals(ProblemType.NOT_FOUND, failure(() -> service.link(USER, UUID.randomUUID())).type());
    }
}
