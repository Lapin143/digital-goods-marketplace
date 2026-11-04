package dgm.catalog.sellers.apikey.service;

import dgm.catalog.sellers.apikey.domain.ApiKey;
import dgm.catalog.sellers.apikey.domain.ApiKeyForm;
import dgm.catalog.sellers.apikey.domain.ApiKeyPage;
import dgm.catalog.sellers.apikey.domain.ApiKeyStatus;
import dgm.catalog.sellers.apikey.domain.ApiKeyValues;
import dgm.catalog.sellers.apikey.domain.IssuedKey;
import dgm.catalog.sellers.apikey.domain.KeyCheck;
import dgm.catalog.sellers.apikey.repository.ApiKeyRepository;
import dgm.catalog.sellers.domain.FieldViolation;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.repository.SellerRepository;
import dgm.catalog.sellers.service.SellerTransaction;
import dgm.kit.id.Uuids;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * API-ключи продавца (OP-90 – OP-92, OP-96; US-11.1, US-11.2). Ключ выпускается и отзывается только по токену с вторым фактором (это
 * проверяет фильтр каркаса по правилам маршрутов), профиль продавца должен быть «одобрен» (U3).
 *
 * <p>Выпуск: строка профиля блокируется на время транзакции, поэтому «посчитал действующие и вставил» не может пройти дважды для одного
 * продавца: не больше пяти действующих ключей, шестой получает 409 {@code api-key-limit} (U20; триггер базы держит тот же предел как
 * вторая линия защиты). Строка ключа, событие {@code api-key.issued} и запись аудита фиксируются одной транзакцией (INV-43). Значение ключа
 * существует только в возвращаемом {@link IssuedKey}: в базу, события, аудит и журнал оно не попадает (INV-41).
 *
 * <p>Отзыв необратим и идемпотентен по самому ключу: повтор отзыва отдаёт прежнее состояние без второго события и второй записи аудита.
 */
@Component
public final class ApiKeyService {

    /** Не больше столько действующих ключей на продавца (Р-185). */
    public static final int ACTIVE_LIMIT = 5;

    private final ApiKeyRepository keys;
    private final SellerRepository sellers;
    private final SellerTransaction transaction;
    private final Clock clock;
    private final ApiKeyValues.RandomBytes random;

    public ApiKeyService(ApiKeyRepository keys, SellerRepository sellers, SellerTransaction transaction, Clock clock,
                         ApiKeyValues.RandomBytes random) {
        this.keys = keys;
        this.sellers = sellers;
        this.transaction = transaction;
        this.clock = clock;
        this.random = random;
    }

    /**
     * Выпуск ключа.
     *
     * @param sellerId профиль продавца из claim {@code seller_id}
     * @param actorId  пользователь, выпускающий ключ (для аудита)
     */
    public IssuedKey issue(UUID sellerId, UUID actorId, ApiKeyForm form) {
        List<FieldViolation> violations = form.violations();
        if (!violations.isEmpty()) {
            throw SellerProfile.validation(violations);
        }
        return transaction.execute(() -> {
            requireApproved(sellers.lockById(sellerId));
            if (keys.countActive(sellerId) >= ACTIVE_LIMIT) {
                throw new ProblemException(ProblemType.API_KEY_LIMIT, "У продавца уже пять активных API-ключей, отзовите ненужный.",
                        Map.of("limit", ACTIVE_LIMIT), Map.of());
            }
            Instant now = now();
            String value = ApiKeyValues.generate(random);
            ApiKey key = ApiKey.issue(Uuids.v7(clock), sellerId, form.name(), ApiKeyValues.fingerprint(value), now);
            keys.insert(key, ApiKeyValues.hash(value));
            transaction.publish(ApiKeyEvents.TOPIC, ApiKeyEvents.AGGREGATE, key.id().toString(), ApiKeyEvents.ISSUED, ApiKeyEvents.SCHEMA_VERSION,
                    ApiKeyEvents.event(key));
            transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, key.id().toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    ApiKeyEvents.issuedAudit(now, actorId, key));
            return new IssuedKey(key, value);
        });
    }

    /** Ключи продавца, новые выше. Читается на одну строку больше страницы, чтобы узнать, есть ли следующая. */
    public ApiKeyPage list(UUID sellerId, Optional<ApiKeyStatus> status, int limit, Optional<ApiKeyPage.After> after) {
        List<ApiKey> rows = transaction.execute(() -> {
            requireApproved(sellers.findById(sellerId));
            return keys.list(sellerId, status, after, limit + 1);
        });
        boolean more = rows.size() > limit;
        List<ApiKey> items = more ? rows.subList(0, limit) : rows;
        Optional<ApiKeyPage.After> next = more
                ? Optional.of(new ApiKeyPage.After(items.get(limit - 1).createdAt(), items.get(limit - 1).id()))
                : Optional.empty();
        return new ApiKeyPage(List.copyOf(items), limit, next);
    }

    /**
     * Отзыв ключа. Чужой и несуществующий ключ неразличимы: 404 (U19). Уже отозванный ключ возвращается как есть, с прежней датой отзыва.
     */
    public ApiKey revoke(UUID sellerId, UUID actorId, UUID apiKeyId) {
        return transaction.execute(() -> {
            requireApproved(sellers.findById(sellerId));
            ApiKey before = keys.lock(sellerId, apiKeyId).orElseThrow(ApiKeyService::notFound);
            if (!before.active()) {
                return before;
            }
            Instant now = now();
            ApiKey after = before.revoke(now);
            if (!keys.markRevoked(after)) {
                // Строка заблокирована с начала отзыва, поэтому статус не мог измениться; проверка защищает от ошибки в коде
                throw new IllegalStateException("Ключ " + apiKeyId + " изменился под блокировкой");
            }
            transaction.publish(ApiKeyEvents.TOPIC, ApiKeyEvents.AGGREGATE, after.id().toString(), ApiKeyEvents.REVOKED, ApiKeyEvents.SCHEMA_VERSION,
                    ApiKeyEvents.event(after));
            transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, after.id().toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    ApiKeyEvents.revokedAudit(now, actorId, before, after));
            return after;
        });
    }

    /**
     * Проверка для шлюза (OP-96): ключ по SHA-256 значения. Пусто для неизвестного хеша; отозванный ключ тоже возвращается, а решает
     * контроллер: для шлюза неизвестный и отозванный ключ один и тот же ответ без причины.
     */
    public Optional<KeyCheck> check(byte[] keyHash) {
        return transaction.execute(() -> keys.findByHash(keyHash));
    }

    private Instant now() {
        // Миллисекунды: так время видно в ответах, а база округляет до микросекунд и расхождения между выпуском и списком не возникает
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireApproved(Optional<SellerProfile> profile) {
        if (profile.isEmpty() || profile.get().status() != SellerStatus.APPROVED) {
            throw new ProblemException(ProblemType.FORBIDDEN, "Профиль продавца не одобрен: API-ключи доступны после одобрения заявки.");
        }
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "API-ключ не найден.");
    }
}
