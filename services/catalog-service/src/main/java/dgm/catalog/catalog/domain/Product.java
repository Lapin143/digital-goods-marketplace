package dgm.catalog.catalog.domain;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Товар (SM-04). Объект неизменяемый: действие продавца возвращает новое состояние или отказывает ошибкой-проблемой, которую сервис
 * отдаёт клиенту как есть. Время приходит параметром из часов сервиса, поэтому сроки проверяются подменой времени. Версия растёт
 * при каждом изменении строки и служит ETag и условием {@code If-Match} (параллельная правка не теряется).
 *
 * @param rejectionReason  причина отклонения, видна продавцу, пока товар отклонён
 * @param statusBeforeBlock статус до блокировки: заполнен только у заблокированного (INV-40)
 * @param blockReason      причина блокировки: заполнена только у заблокированного
 * @param blockNote        текст причины, который ввёл модератор (Р-21), виден продавцу; только у заблокированного
 * @param submittedAt      когда товар встал в очередь модератора
 * @param reviewDeadlineAt срок рассмотрения: подача плюс срок проверки (3 суток, FT-3.2)
 * @param overdueMarkedAt  когда задание контроля сроков пометило просрочку, один раз (US-8.8)
 * @param publishedAt      последняя публикация: порядок витрины «новые выше»
 */
public record Product(UUID id, UUID sellerId, ProductForm form, String rejectionReason, ProductStatus statusBeforeBlock,
                      BlockReason blockReason, String blockNote, Instant submittedAt, Instant reviewDeadlineAt, Instant overdueMarkedAt,
                      Instant publishedAt, ProductStatus status, int version, Instant createdAt, Instant updatedAt) {

    /** Результат правки: новое состояние и имена изменённых полей (вместе со {@code status}, если правка сменила статус). */
    public record Edited(Product product, List<String> changedFields) {

        public boolean changed() {
            return !changedFields.isEmpty();
        }
    }

    /** SM-04/T1: товар создан в черновике, на витрине его нет. */
    public static Product create(UUID id, UUID sellerId, ProductForm form, Instant now) {
        requireValid(form);
        return new Product(id, sellerId, form, null, null, null, null, null, null, null, null, ProductStatus.DRAFT, 1, now, now);
    }

    /**
     * Правка полей ({@code PUT} заменяет все). Черновик, отклонённый и заблокированный по решению модератора товар сохраняют правку и
     * остаются в своём статусе. Опубликованный: название, описание или тип возвращают его на модерацию, срок проверки идёт заново (T6);
     * цена, платформа, регион и способ выдачи статус не меняют (T7). Правка без отличий ничего не меняет: версия и событие не нужны.
     *
     * @throws ProblemException {@code state-conflict}, если товар на модерации, в архиве или заблокирован вместе с продавцом
     */
    public Edited replace(ProductForm next, Instant now, Duration reviewPeriod) {
        requireEditable();
        requireValid(next);
        List<String> changed = new ArrayList<>(form.differences(next));
        if (changed.isEmpty()) {
            return new Edited(this, List.of());
        }
        ProductStatus target = status;
        Instant submitted = submittedAt;
        Instant deadline = reviewDeadlineAt;
        Instant overdue = overdueMarkedAt;
        if (status == ProductStatus.PUBLISHED && ProductForm.needsModeration(changed)) {
            target = ProductStatus.ON_MODERATION;
            submitted = now;
            deadline = now.plus(reviewPeriod);
            overdue = null;
            changed.add("status");
        }
        return new Edited(new Product(id, sellerId, next, rejectionReason, statusBeforeBlock, blockReason, blockNote, submitted, deadline,
                overdue, publishedAt, target, version + 1, createdAt, now), List.copyOf(changed));
    }

    /**
     * Товар уходит в очередь модератора: T2 из черновика, T5 после отклонения, T11 после блокировки модератором. Срок проверки идёт
     * от этой подачи. Повторная отправка товара на модерации срок не сбрасывает и даёт отказ.
     */
    public Product submit(Instant now, Duration reviewPeriod) {
        boolean allowed = status == ProductStatus.DRAFT || status == ProductStatus.REJECTED || moderatorBlocked();
        if (!allowed) {
            throw stateConflict();
        }
        return new Product(id, sellerId, form, null, null, null, null, now, now.plus(reviewPeriod), null, publishedAt,
                ProductStatus.ON_MODERATION, version + 1, createdAt, now);
    }

    /**
     * T12: товар снят с продажи. Из очереди модератора он выходит (срок проверки сбрасывается). Заблокированный товар в архив не
     * уходит: он должен остаться виден модератору.
     */
    public Product archive(Instant now) {
        boolean allowed = status == ProductStatus.DRAFT || status == ProductStatus.ON_MODERATION || status == ProductStatus.PUBLISHED
                || status == ProductStatus.REJECTED;
        if (!allowed) {
            throw stateConflict();
        }
        return new Product(id, sellerId, form, null, null, null, null, null, null, null, publishedAt, ProductStatus.ARCHIVED, version + 1,
                createdAt, now);
    }

    /** T13: товар возвращён из архива в черновик; пул ключей сохраняется, публикация снова через модерацию. */
    public Product restore(Instant now) {
        if (status != ProductStatus.ARCHIVED) {
            throw stateConflict();
        }
        return new Product(id, sellerId, form, null, null, null, null, null, null, null, publishedAt, ProductStatus.DRAFT, version + 1,
                createdAt, now);
    }

    /**
     * T3: модератор одобрил товар из очереди. Товар публикуется (порядок витрины «новые выше» идёт от этого момента), срок проверки
     * закрывается, причина прошлого отклонения снимается.
     */
    public Product approve(Instant now) {
        requireOnModeration();
        return new Product(id, sellerId, form, null, null, null, null, null, null, null, now, ProductStatus.PUBLISHED, version + 1, createdAt, now);
    }

    /** T4: модератор отклонил товар из очереди; причина видна продавцу, исправленный товар он отправляет заново (T5). */
    public Product reject(String reason, Instant now) {
        requireOnModeration();
        return new Product(id, sellerId, form, reason, null, null, null, null, null, null, publishedAt, ProductStatus.REJECTED, version + 1,
                createdAt, now);
    }

    /**
     * T8: модератор заблокировал опубликованный товар; он исчезает с витрины, текст причины виден продавцу (Р-21). Путь назад идёт через
     * правку продавца и новую модерацию (T11).
     */
    public Product block(String note, Instant now) {
        if (status != ProductStatus.PUBLISHED) {
            throw stateConflict();
        }
        return new Product(id, sellerId, form, null, ProductStatus.PUBLISHED, BlockReason.MODERATOR_DECISION, note, null, null, null,
                publishedAt, ProductStatus.BLOCKED, version + 1, createdAt, now);
    }

    /** Черновик модератору не виден: пока продавец не отправил товар, его как будто нет (Р-73 для товаров). */
    public boolean visibleToModerator() {
        return status != ProductStatus.DRAFT;
    }

    private void requireOnModeration() {
        if (status != ProductStatus.ON_MODERATION) {
            throw stateConflict();
        }
    }

    /** Блокировка по решению модератора: продавец может исправить товар и отправить заново (T11). */
    public boolean moderatorBlocked() {
        return status == ProductStatus.BLOCKED && blockReason == BlockReason.MODERATOR_DECISION;
    }

    /** Правка допустима в черновике, после отклонения, в опубликованном и заблокированном модератором товаре. */
    public void requireEditable() {
        boolean allowed = status == ProductStatus.DRAFT || status == ProductStatus.REJECTED || status == ProductStatus.PUBLISHED
                || moderatorBlocked();
        if (!allowed) {
            throw stateConflict();
        }
    }

    public ProblemException stateConflict() {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("currentStatus", status.code());
        return new ProblemException(ProblemType.STATE_CONFLICT, "Действие недоступно в текущем статусе товара.", extensions, Map.of());
    }

    private static void requireValid(ProductForm form) {
        if (!form.violations().isEmpty()) {
            throw new IllegalArgumentException("Форма товара с нарушениями не принимается: " + form.violations());
        }
    }
}
