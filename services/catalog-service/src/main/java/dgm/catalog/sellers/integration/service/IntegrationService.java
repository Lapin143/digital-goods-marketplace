package dgm.catalog.sellers.integration.service;

import dgm.catalog.sellers.domain.FieldViolation;
import dgm.catalog.sellers.domain.SellerProfile;
import dgm.catalog.sellers.domain.SellerStatus;
import dgm.catalog.sellers.integration.crypto.IntegrationSecrets;
import dgm.catalog.sellers.integration.domain.IntegrationForm;
import dgm.catalog.sellers.integration.domain.IntegrationStatus;
import dgm.catalog.sellers.integration.domain.RotatedSecret;
import dgm.catalog.sellers.integration.domain.SavedIntegration;
import dgm.catalog.sellers.integration.domain.SellerIntegration;
import dgm.catalog.sellers.integration.repository.IntegrationRepository;
import dgm.catalog.sellers.repository.SellerRepository;
import dgm.catalog.sellers.service.SellerTransaction;
import dgm.kit.client.EndpointGuard;
import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Подключение системы продавца (OP-93 – OP-95, OP-97; US-11.3, US-11.4, Р-187, ADR-028). Читает, сохраняет и меняет секрет только
 * продавец с одобренным профилем (U3) по токену со вторым фактором; это проверяют фильтр каркаса и правила маршрутов.
 *
 * <p><b>Адрес.</b> Перед сохранением адрес проходит {@link EndpointGuard}: {@code https}, порт 443 или 8443, до 300 знаков, без учётных данных
 * и фрагмента, имя разрешается, и ни один из его адресов не внутренний. Отказ это 422 {@code endpoint-not-allowed} с одним общим текстом: какой
 * именно признак сработал, не раскрывается. Проверка стоит до транзакции:
 * разрешение имени это сетевой вызов, и он не должен держать соединение с базой. Одной этой проверки мало: имя можно перенаправить на внутренний
 * адрес после сохранения, поэтому адрес проверяется заново при каждом вызове платформы (U21), здесь же только первая линия.
 *
 * <p><b>Версия записи.</b> {@code PUT} идёт с {@code If-Match}: версия записи, а для первого подключения {@code 0}. Строка профиля продавца
 * блокируется на время транзакции, поэтому две гонки за одно подключение идут по очереди: вторая видит новую версию и получает 412. Повтор
 * точно такого же состояния (тот же адрес и статус, актуальная версия) ничего не меняет: ни версии, ни записи аудита.
 *
 * <p><b>Секрет.</b> Секрет не хранится: он считается из мастер-секрета, идентификатора продавца и версии секрета ({@link IntegrationSecrets}).
 * Продавец видит его один раз: при первом подключении и при смене. Повторный {@code PUT} секрета не возвращает; потерявший секрет меняет его
 * через {@link #rotate}. Смена увеличивает версию, и прежний секрет перестаёт действовать сразу: отправитель всегда подписывает по актуальной
 * версии, наложения нет.
 *
 * <p>Изменение подключения и запись аудита фиксируются одной транзакцией (INV-43). В аудит идут версии, статусы и SHA-256 частей адреса, но не
 * секрет и не адрес.
 */
@Component
public final class IntegrationService {

    /** Один общий текст отказа: по нему нельзя понять, какой признак адреса сработал (Р-187). */
    static final String NOT_ALLOWED = "Адрес системы продавца не прошёл проверку: нужен https, внутренние адреса и посторонние порты не допускаются.";

    private final IntegrationRepository integrations;
    private final SellerRepository sellers;
    private final SellerTransaction transaction;
    private final EndpointGuard guard;
    private final IntegrationSecrets secrets;
    private final Clock clock;

    public IntegrationService(IntegrationRepository integrations, SellerRepository sellers, SellerTransaction transaction, EndpointGuard guard,
                              IntegrationSecrets secrets, Clock clock) {
        this.integrations = integrations;
        this.sellers = sellers;
        this.transaction = transaction;
        this.guard = guard;
        this.secrets = secrets;
        this.clock = clock;
    }

    /** Подключение продавца; пусто, пока система не подключена (ответ кабинета: пустой адрес, версия 0). */
    public Optional<SellerIntegration> get(UUID sellerId) {
        return transaction.execute(() -> {
            requireApproved(sellers.findById(sellerId));
            return integrations.find(sellerId);
        });
    }

    /**
     * Сохраняет адрес и статус.
     *
     * @param sellerId        профиль продавца из claim {@code seller_id}
     * @param actorId         пользователь, сохраняющий подключение (для аудита)
     * @param expectedVersion версия записи из {@code If-Match}; {@code 0}, если подключения ещё нет
     */
    public SavedIntegration set(UUID sellerId, UUID actorId, long expectedVersion, IntegrationForm form) {
        List<FieldViolation> violations = form.violations();
        if (!violations.isEmpty()) {
            throw SellerProfile.validation(violations);
        }
        // Профиль проверяется до разрешения имени: неодобренный продавец не должен заставлять платформу ходить в DNS
        transaction.execute(() -> {
            requireApproved(sellers.findById(sellerId));
            return null;
        });
        String url = checkedUrl(form.endpointUrl());
        IntegrationStatus status = form.status() == null ? IntegrationStatus.ACTIVE : form.status();
        return transaction.execute(() -> {
            requireApproved(sellers.lockById(sellerId));
            Optional<SellerIntegration> before = integrations.find(sellerId);
            long actual = before.map(SellerIntegration::version).orElse(0L);
            if (expectedVersion != actual) {
                throw versionConflict();
            }
            Instant now = now();
            if (before.isEmpty()) {
                SellerIntegration created = SellerIntegration.connect(sellerId, url, status, now);
                integrations.insert(created);
                audit(now, actorId, Optional.empty(), created);
                return new SavedIntegration(created, secrets.reveal(sellerId, created.secretVersion()));
            }
            SellerIntegration current = before.get();
            if (current.endpointUrl().equals(url) && current.status() == status) {
                return new SavedIntegration(current, null);
            }
            SellerIntegration changed = integrations.update(sellerId, url, status);
            audit(now, actorId, before, changed);
            return new SavedIntegration(changed, null);
        });
    }

    /**
     * Смена секрета: новая версия, прежний секрет недействителен сразу. Подключения нет: 409 {@code state-conflict} (U4). Повтор по тому же
     * {@code Idempotency-Key} охранник каркаса отдаёт сохранённым ответом без секрета (Р-198), сюда он не доходит.
     */
    public RotatedSecret rotate(UUID sellerId, UUID actorId) {
        return transaction.execute(() -> {
            requireApproved(sellers.lockById(sellerId));
            SellerIntegration before = integrations.find(sellerId).orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT,
                    "Система продавца не подключена: сначала укажите её адрес."));
            SellerIntegration after = integrations.rotateSecret(sellerId);
            Instant now = now();
            transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, sellerId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    IntegrationEvents.rotatedAudit(now, actorId, before, after));
            return new RotatedSecret(after.secretVersion(), secrets.reveal(sellerId, after.secretVersion()));
        });
    }

    /**
     * Адрес для вызывающих сервисов (OP-97): подключение действует, и профиль продавца одобрен. Подключения нет, оно отключено или продавец
     * заблокирован или ещё не одобрен: пусто, и вызывающий не отправляет ничего (404 без причины). Адрес вызывающий проверяет сам перед
     * каждым вызовом.
     */
    public Optional<SellerIntegration> activeEndpoint(UUID sellerId) {
        return transaction.execute(() -> {
            Optional<SellerProfile> profile = sellers.findById(sellerId);
            if (profile.isEmpty() || profile.get().status() != SellerStatus.APPROVED) {
                return Optional.empty();
            }
            return integrations.find(sellerId).filter(SellerIntegration::active);
        });
    }

    /**
     * Проверка адреса до транзакции. Принимается только запись {@code https://…} в нижнем регистре схемы: так записано в ограничении
     * {@code ck_seller_integration_endpoint_url}, а охранник схему от регистра не различает.
     */
    private String checkedUrl(String url) {
        try {
            guard.check(url);
        } catch (EndpointGuard.RejectedException e) {
            throw new ProblemException(ProblemType.ENDPOINT_NOT_ALLOWED, NOT_ALLOWED);
        }
        if (!url.startsWith("https://") || url.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new ProblemException(ProblemType.ENDPOINT_NOT_ALLOWED, NOT_ALLOWED);
        }
        return url;
    }

    private void audit(Instant now, UUID actorId, Optional<SellerIntegration> before, SellerIntegration after) {
        IntegrationEvents.setAudit(now, actorId, before, after).ifPresent(record -> transaction.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE,
                after.sellerId().toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION, record));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireApproved(Optional<SellerProfile> profile) {
        if (profile.isEmpty() || profile.get().status() != SellerStatus.APPROVED) {
            throw new ProblemException(ProblemType.FORBIDDEN, "Профиль продавца не одобрен: подключение системы доступно после одобрения заявки.");
        }
    }

    private static ProblemException versionConflict() {
        return new ProblemException(ProblemType.VERSION_CONFLICT, "Подключение изменилось: прочитайте его заново и повторите с новым If-Match.");
    }
}
