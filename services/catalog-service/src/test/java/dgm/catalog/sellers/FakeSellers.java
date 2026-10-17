package dgm.catalog.sellers;

import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.domain.SellerType;
import dgm.catalog.sellers.repository.SellerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий профилей и документов в памяти для модульных тестов. Повторяет то, на что полагается сервис: один профиль на
 * пользователя, запись только при совпадении версии, документы в порядке загрузки, пометка просрочки один раз.
 */
public final class FakeSellers implements SellerRepository {

    public static final UUID USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    public static final UUID OTHER_USER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a2");

    private final Map<UUID, SellerProfile> profiles = new LinkedHashMap<>();
    private final Map<UUID, List<SellerDocument>> documents = new LinkedHashMap<>();
    /** Следующая вставка профиля проиграет гонку: вернёт {@code false}, как при нарушении уникальности user_id. */
    public boolean loseNextInsertRace;
    /** Вставка документа падает, как при сбое базы после записи файла. */
    public boolean failDocumentInsert;

    public static SellerForm completeForm() {
        return new SellerForm(SellerType.SELF_EMPLOYED, "Студия «Лунный свет»", "Иванов Иван Иванович", "Банк «Пример»", "044525000",
                "40817810000000000001", "Ключи к инди-играм для Steam");
    }

    public static SellerProfile profile(UUID userId, SellerStatus status, SellerForm form, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("profile-" + userId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        boolean review = status == SellerStatus.ON_REVIEW;
        boolean decided = status == SellerStatus.REJECTED || status == SellerStatus.RETURNED;
        return new SellerProfile(id, userId, status, form, decided ? "Причина решения" : null,
                status == SellerStatus.REJECTED ? now : null, review || decided || status == SellerStatus.APPROVED ? now : null,
                review ? now.plusSeconds(259_200) : null, null, 1, now, now);
    }

    /** Кладёт профиль как есть, минуя правила: для подготовки состояния теста. */
    public FakeSellers put(SellerProfile profile) {
        profiles.put(profile.userId(), profile);
        return this;
    }

    public FakeSellers putDocument(SellerDocument document) {
        documents.computeIfAbsent(document.sellerId(), k -> new ArrayList<>()).add(document);
        return this;
    }

    public SellerProfile stored(UUID userId) {
        return profiles.get(userId);
    }

    public int profileCount() {
        return profiles.size();
    }

    @Override
    public Optional<SellerProfile> findByUser(UUID userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public Optional<SellerProfile> lockByUser(UUID userId) {
        return findByUser(userId);
    }

    @Override
    public boolean insert(SellerProfile profile) {
        if (loseNextInsertRace) {
            loseNextInsertRace = false;
            profiles.putIfAbsent(profile.userId(), profile(profile.userId(), SellerStatus.DRAFT, SellerForm.empty(), profile.createdAt()));
            return false;
        }
        return profiles.putIfAbsent(profile.userId(), profile) == null;
    }

    @Override
    public boolean update(SellerProfile profile, int expectedVersion) {
        SellerProfile current = profiles.get(profile.userId());
        if (current == null || current.version() != expectedVersion) {
            return false;
        }
        profiles.put(profile.userId(), profile);
        return true;
    }

    @Override
    public List<SellerDocument> documents(UUID sellerId) {
        List<SellerDocument> list = new ArrayList<>(documents.getOrDefault(sellerId, List.of()));
        list.sort(Comparator.comparing(SellerDocument::uploadedAt).thenComparing(SellerDocument::id));
        return list;
    }

    @Override
    public int countDocuments(UUID sellerId) {
        return documents.getOrDefault(sellerId, List.of()).size();
    }

    @Override
    public void insertDocument(SellerDocument document) {
        if (failDocumentInsert) {
            throw new IllegalStateException("сбой базы");
        }
        putDocument(document);
    }

    @Override
    public Optional<SellerDocument> findDocument(UUID sellerId, UUID documentId) {
        return documents.getOrDefault(sellerId, List.of()).stream().filter(d -> d.id().equals(documentId)).findFirst();
    }

    @Override
    public boolean deleteDocument(UUID sellerId, UUID documentId) {
        return documents.getOrDefault(sellerId, new ArrayList<>()).removeIf(d -> d.id().equals(documentId));
    }

    @Override
    public Optional<SellerDocument> findUserDocument(UUID userId, UUID documentId) {
        return findByUser(userId).flatMap(p -> findDocument(p.id(), documentId));
    }

    @Override
    public List<UUID> markOverdue(Instant now) {
        List<UUID> marked = new ArrayList<>();
        for (SellerProfile p : List.copyOf(profiles.values())) {
            if (p.status() == SellerStatus.ON_REVIEW && p.overdueMarkedAt() == null && !p.reviewDeadlineAt().isAfter(now)) {
                profiles.put(p.userId(), new SellerProfile(p.id(), p.userId(), p.status(), p.form(), p.decisionReason(), p.lastRejectedAt(),
                        p.submittedAt(), p.reviewDeadlineAt(), now, p.version(), p.createdAt(), p.updatedAt()));
                marked.add(p.id());
            }
        }
        return marked;
    }
}
