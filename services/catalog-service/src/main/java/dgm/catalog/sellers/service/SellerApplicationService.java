package dgm.catalog.sellers.service;

import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerForm;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.repository.SellerRepository;
import dgm.kit.id.Uuids;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Заявка продавца со стороны заявителя (OP-10, OP-14): начать, показать, заменить анкету, отправить на проверку. Правила статусов и
 * времени лежат в {@link SellerProfile}, здесь порядок проверок и запись. Порядок отказов: нет профиля (404), статус (409), версия
 * анкеты (412), полнота анкеты (422).
 */
@Component
public final class SellerApplicationService {

    /** Заявка с документами, как её видит заявитель. */
    public record Application(SellerProfile profile, List<SellerDocument> documents) {
    }

    private final SellerRepository repository;
    private final SellerTransaction transaction;
    private final Clock clock;
    private final SellerSettings settings;

    public SellerApplicationService(SellerRepository repository, SellerTransaction transaction, Clock clock, SellerSettings settings) {
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
        this.settings = settings;
    }

    public SellerSettings settings() {
        return settings;
    }

    /** SM-05/T1. У пользователя один профиль (INV-39): если он есть, 409 с текущим статусом. */
    public Application create(UUID userId, SellerForm form) {
        return transaction.execute(() -> {
            var existing = repository.findByUser(userId);
            if (existing.isPresent()) {
                throw alreadyStarted(existing.get());
            }
            SellerProfile profile = SellerProfile.create(Uuids.v7(clock), userId, form, clock.instant());
            if (!repository.insert(profile)) {
                // Параллельный запрос успел раньше: уникальный индекс user_id оставил одну строку
                throw alreadyStarted(repository.findByUser(userId).orElseThrow());
            }
            return new Application(profile, List.of());
        });
    }

    public Application current(UUID userId) {
        return transaction.execute(() -> {
            SellerProfile profile = repository.findByUser(userId).orElseThrow(SellerApplicationService::notFound);
            return new Application(profile, repository.documents(profile.id()));
        });
    }

    /**
     * Анкета заменяется целиком. {@code expectedVersion} это версия из {@code If-Match}; в отказе правка возвращает профиль в черновик (T6).
     */
    public Application replace(UUID userId, long expectedVersion, SellerForm form) {
        return transaction.execute(() -> {
            SellerProfile profile = repository.lockByUser(userId).orElseThrow(SellerApplicationService::notFound);
            profile.requireEditable();
            if (profile.version() != expectedVersion) {
                throw versionConflict();
            }
            SellerProfile saved = profile.replaceForm(form, clock.instant());
            save(saved, profile.version());
            return new Application(saved, repository.documents(saved.id()));
        });
    }

    /** SM-05/T2 и T7: заявка встаёт в очередь модератора, срок рассмотрения отсчитывается заново. */
    public Application submit(UUID userId) {
        return transaction.execute(() -> {
            SellerProfile profile = repository.lockByUser(userId).orElseThrow(SellerApplicationService::notFound);
            List<SellerDocument> documents = repository.documents(profile.id());
            SellerProfile submitted = profile.submit(documents.size(), clock.instant(), settings.reapplicationPause(), settings.reviewPeriod());
            save(submitted, profile.version());
            return new Application(submitted, documents);
        });
    }

    private void save(SellerProfile profile, int expectedVersion) {
        if (!repository.update(profile, expectedVersion)) {
            throw versionConflict();
        }
    }

    static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Заявка продавца не найдена: сначала начните её.");
    }

    private static ProblemException versionConflict() {
        return new ProblemException(ProblemType.VERSION_CONFLICT, "Анкета изменилась, перечитайте её и повторите с новым ETag.");
    }

    private static ProblemException alreadyStarted(SellerProfile existing) {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("currentStatus", existing.status().code());
        return new ProblemException(ProblemType.STATE_CONFLICT,
                "Заявка уже начата, анкету правят через PUT /api/v1/seller-applications/current.", extensions, Map.of());
    }
}
