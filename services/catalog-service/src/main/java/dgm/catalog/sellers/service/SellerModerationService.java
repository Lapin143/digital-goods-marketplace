package dgm.catalog.sellers.service;

import dgm.catalog.sellers.domain.Moderator;
import dgm.catalog.sellers.domain.ReviewQueueItem;
import dgm.catalog.sellers.domain.ReviewQueuePage;
import dgm.catalog.sellers.domain.SellerDocument;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerSettings;
import dgm.catalog.sellers.repository.SellerRepository;
import dgm.catalog.sellers.service.SellerApplicationService.Application;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.storage.ObjectStorage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Заявки продавцов со стороны модератора (OP-11, OP-12; US-2.2, US-2.3): очередь, карточка с документами и три решения. Правила
 * статусов лежат в {@link SellerProfile}, здесь порядок действий.
 *
 * <p>Решение, событие в Outbox и запись аудита фиксируются одной транзакцией (INV-43, правило 1 среза): без любого из трёх не остаётся и
 * остальных. Строка профиля блокируется на время решения, поэтому два модератора, решающих одну заявку, идут по очереди, и второй получает
 * 409 с текущим статусом. Профиль, который заявитель ещё не отправлял (черновик), для модератора не существует: 404.
 */
@Component
public final class SellerModerationService {

    /** Карточка заявки: профиль и его документы со ссылками на 5 минут. */
    public record Card(SellerProfile profile, List<CardDocument> documents) {
    }

    /** Документ в карточке со ссылкой, подписанной на срок {@code seller.document-link-ttl-seconds}. */
    public record CardDocument(SellerDocument document, ObjectStorage.PresignedLink link) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(SellerModerationService.class);
    private static final int DOCUMENT_RETRY_AFTER_SECONDS = 5;

    private final SellerRepository repository;
    private final SellerTransaction transaction;
    private final ObjectStorage storage;
    private final Clock clock;
    private final SellerSettings settings;

    public SellerModerationService(SellerRepository repository, SellerTransaction transaction, ObjectStorage storage, Clock clock,
                                   SellerSettings settings) {
        this.repository = repository;
        this.transaction = transaction;
        this.storage = storage;
        this.clock = clock;
        this.settings = settings;
    }

    public SellerSettings settings() {
        return settings;
    }

    /** Очередь на проверке: старые выше. Читается на одно значение больше размера страницы, чтобы узнать, есть ли следующая. */
    public ReviewQueuePage queue(int limit, Optional<ReviewQueuePage.After> after) {
        List<ReviewQueueItem> rows = transaction.execute(() -> repository.reviewQueue(after, limit + 1));
        boolean more = rows.size() > limit;
        List<ReviewQueueItem> items = more ? rows.subList(0, limit) : rows;
        Optional<ReviewQueuePage.After> next = more
                ? Optional.of(new ReviewQueuePage.After(items.get(items.size() - 1).submittedAt(), items.get(items.size() - 1).sellerId()))
                : Optional.empty();
        return new ReviewQueuePage(List.copyOf(items), limit, next, clock.instant());
    }

    /**
     * Карточка: анкета и ссылки на документы. Открытие ничего не меняет в заявке, но чтение документа это действие (ADR-014, Р-166): на
     * каждую выдаваемую ссылку в Outbox ложится запись {@code seller.document_opened}, и она фиксируется до выдачи ссылок. Не удалось записать,
     * ответ 503 и ни одной ссылки: так документ не открыть без следа. Подпись ссылок не обращается к хранилищу и идёт вне транзакции.
     * Запись идёт в той же транзакции, что чтение анкеты, поэтому у несуществующей заявки записей нет.
     */
    public Card card(Moderator moderator, UUID sellerId) {
        Application application = transaction.execute(() -> {
            SellerProfile profile = repository.findById(sellerId).filter(SellerProfile::visibleToModerator).orElseThrow(SellerModerationService::notFound);
            List<SellerDocument> documents = repository.documents(sellerId);
            recordOpenings(moderator, sellerId, documents);
            return new Application(profile, documents);
        });
        List<CardDocument> documents = new ArrayList<>();
        for (SellerDocument document : application.documents()) {
            documents.add(new CardDocument(document, storage.presignGet(document.objectKey(), settings.documentLinkTtl())));
        }
        return new Card(application.profile(), List.copyOf(documents));
    }

    /** По одной записи на документ; любой сбой откатывает чтение и даёт 503 (текст ошибки может нести значения, в журнал идёт только класс). */
    private void recordOpenings(Moderator moderator, UUID sellerId, List<SellerDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        try {
            for (SellerDocument document : documents) {
                transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, sellerId.toString(), AuditRecords.EVENT,
                        AuditRecords.SCHEMA_VERSION, SellerEvents.documentOpened(now, moderator, sellerId, document.id()));
            }
        } catch (RuntimeException e) {
            LOG.warn("Запись об открытии документов заявки не зафиксирована, ссылки не выдаются: {}", e.getClass().getSimpleName());
            throw ProblemException.dependencyUnavailable("Документы временно недоступны, повторите запрос позже.", DOCUMENT_RETRY_AFTER_SECONDS);
        }
    }

    /** SM-05/T3. Роль «Продавец» назначит {@code platform-service} по событию {@code seller.approved} (INV-36, INV-37). */
    public Application approve(Moderator moderator, UUID sellerId) {
        return decide(moderator, sellerId, SellerEvents.APPROVED, (profile, now) -> profile.approve(now));
    }

    /** SM-05/T4: причина обязательна, от этого момента идёт пауза перед новой заявкой. */
    public Application reject(Moderator moderator, UUID sellerId, String reason) {
        return decide(moderator, sellerId, SellerEvents.REJECTED, (profile, now) -> profile.reject(reason, now));
    }

    /** SM-05/T5: комментарий обязателен, паузы нет. */
    public Application returnForRework(Moderator moderator, UUID sellerId, String comment) {
        return decide(moderator, sellerId, SellerEvents.RETURNED, (profile, now) -> profile.returnForRework(comment, now));
    }

    private Application decide(Moderator moderator, UUID sellerId, String decision, BiFunction<SellerProfile, Instant, SellerProfile> change) {
        return transaction.execute(() -> {
            SellerProfile before = repository.lockById(sellerId).filter(SellerProfile::visibleToModerator).orElseThrow(SellerModerationService::notFound);
            Instant now = clock.instant();
            SellerProfile after = change.apply(before, now);
            if (!repository.update(after, before.version())) {
                // Строка заблокирована с начала решения, поэтому версия не могла измениться; проверка защищает от ошибки в коде
                throw before.stateConflict();
            }
            transaction.publish(SellerEvents.TOPIC, SellerEvents.AGGREGATE, sellerId.toString(), decision, SellerEvents.SCHEMA_VERSION,
                    eventData(decision, after, moderator));
            transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, sellerId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    SellerEvents.audit(now, moderator, decision, before, after));
            return new Application(after, repository.documents(sellerId));
        });
    }

    private Map<String, Object> eventData(String decision, SellerProfile after, Moderator moderator) {
        return switch (decision) {
            case SellerEvents.APPROVED -> SellerEvents.approved(after, moderator);
            case SellerEvents.REJECTED -> SellerEvents.rejected(after, moderator, after.lastRejectedAt().plus(settings.reapplicationPause()));
            case SellerEvents.RETURNED -> SellerEvents.returned(after, moderator);
            default -> throw new IllegalArgumentException("Неизвестное решение " + decision);
        };
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Заявка не найдена.");
    }
}
