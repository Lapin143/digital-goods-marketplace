package dgm.catalog.sellers.domain;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Timestamps;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Профиль продавца и его заявка (SM-05). Идентификатор профиля это SellerID во всех контекстах. Объект неизменяемый: каждое действие
 * заявителя возвращает новое состояние или отказывает ошибкой-проблемой, которую сервис отдаёт клиенту как есть. Время приходит
 * параметром, из часов сервиса, поэтому срок и пауза проверяются подменой времени.
 *
 * @param decisionReason   причина отказа или комментарий доработки; при новой подаче очищается
 * @param lastRejectedAt   когда профиль отклонили в последний раз: от этого момента считается пауза перед новой заявкой (FT-2.4)
 * @param submittedAt      когда заявка встала в очередь модератора
 * @param reviewDeadlineAt срок рассмотрения, подача плюс 3 суток (FT-2.1)
 * @param overdueMarkedAt  когда задание контроля сроков пометило просрочку: один раз (US-8.8)
 * @param version          версия для ETag и {@code If-Match}; растёт при каждой смене анкеты или статуса
 */
public record SellerProfile(UUID id, UUID userId, SellerStatus status, SellerForm form, String decisionReason, Instant lastRejectedAt,
                            Instant submittedAt, Instant reviewDeadlineAt, Instant overdueMarkedAt, int version, Instant createdAt,
                            Instant updatedAt) {

    /** SM-05/T1: профиль создан в черновике. */
    public static SellerProfile create(UUID id, UUID userId, SellerForm form, Instant now) {
        return new SellerProfile(id, userId, SellerStatus.DRAFT, form, null, null, null, null, null, 1, now, now);
    }

    /**
     * Анкета заменяется целиком ({@code PUT}). Черновик сохраняет любую анкету; на доработке анкета должна быть полной (Р-20);
     * в отказе правка это SM-05/T6, профиль возвращается в черновик, причина отказа остаётся на виду. Паузу здесь не проверяют:
     * исправлять можно сразу, отправить только после паузы (US-2.5).
     *
     * @throws ProblemException {@code state-conflict}, если заявка на проверке или одобрена; {@code validation-failed}, если на доработке
     *                          анкета неполная
     */
    public SellerProfile replaceForm(SellerForm newForm, Instant now) {
        if (!status.applicantMayEdit()) {
            throw stateConflict();
        }
        if (status == SellerStatus.RETURNED) {
            List<FieldViolation> missing = newForm.missing();
            if (!missing.isEmpty()) {
                throw validation(missing);
            }
        }
        SellerStatus next = status == SellerStatus.REJECTED ? SellerStatus.DRAFT : status;
        return new SellerProfile(id, userId, next, newForm, decisionReason, lastRejectedAt, submittedAt, reviewDeadlineAt, overdueMarkedAt,
                version + 1, createdAt, now);
    }

    /** Анкету и документы можно менять в черновике, на доработке и после отказа; иначе 409 {@code state-conflict}. */
    public void requireEditable() {
        if (!status.applicantMayEdit()) {
            throw stateConflict();
        }
    }

    /**
     * Заявка уходит в очередь модератора: SM-05/T2 из черновика или T7 с доработки. Срок рассмотрения отсчитывается от этой подачи.
     *
     * @param documentCount документов в заявке сейчас
     * @param pause         пауза после отказа (параметр {@code seller.reapplication-pause-seconds})
     * @param reviewPeriod  срок рассмотрения (параметр {@code moderation.review-deadline-seconds})
     * @throws ProblemException {@code application-pause-active}, если с отказа прошло меньше паузы; {@code state-conflict}, если
     *                          заявка уже на проверке, одобрена или отклонена и не исправлена; {@code validation-failed}, если анкета неполна
     *                          или нет документа
     */
    public SellerProfile submit(int documentCount, Instant now, Duration pause, Duration reviewPeriod) {
        switch (status) {
            case DRAFT -> {
                if (pauseActive(now, pause)) {
                    throw pauseActiveProblem(now, pause);
                }
            }
            case RETURNED -> {
                // Доработку отправляют без паузы: она касается только повторной заявки после отказа
            }
            case REJECTED -> {
                // Из отказа в очередь нельзя: сначала правка (T6). Пока пауза идёт, заявитель видит, когда можно подать
                throw pauseActive(now, pause) ? pauseActiveProblem(now, pause) : stateConflict();
            }
            default -> throw stateConflict();
        }
        List<FieldViolation> missing = new ArrayList<>(form.missing());
        if (documentCount < 1) {
            missing.add(new FieldViolation("/documents", "required", "Нужен хотя бы один документ."));
        }
        if (!missing.isEmpty()) {
            throw validation(missing);
        }
        return new SellerProfile(id, userId, SellerStatus.ON_REVIEW, form, null, lastRejectedAt, now, now.plus(reviewPeriod), null,
                version + 1, createdAt, now);
    }

    /**
     * Решение модератора «одобрить»: SM-05/T3. Комментарий и причина пусты, срок рассмотрения остаётся в записи для истории.
     *
     * @throws ProblemException {@code state-conflict} с текущим статусом, если заявка не на проверке (её уже решил другой модератор)
     */
    public SellerProfile approve(Instant now) {
        requireOnReview();
        return decided(SellerStatus.APPROVED, null, lastRejectedAt, now);
    }

    /**
     * Решение «отклонить»: SM-05/T4. Причина записывается, от этого момента идёт пауза перед новой заявкой (FT-2.4).
     *
     * @throws ProblemException {@code state-conflict}, если заявка не на проверке
     */
    public SellerProfile reject(String reason, Instant now) {
        requireOnReview();
        return decided(SellerStatus.REJECTED, reason, now, now);
    }

    /**
     * Решение «вернуть на доработку»: SM-05/T5. Комментарий виден заявителю, паузы нет.
     *
     * @throws ProblemException {@code state-conflict}, если заявка не на проверке
     */
    public SellerProfile returnForRework(String comment, Instant now) {
        requireOnReview();
        return decided(SellerStatus.RETURNED, comment, lastRejectedAt, now);
    }

    private void requireOnReview() {
        if (status != SellerStatus.ON_REVIEW) {
            throw stateConflict();
        }
    }

    private SellerProfile decided(SellerStatus next, String reason, Instant rejectedAt, Instant now) {
        return new SellerProfile(id, userId, next, form, reason, rejectedAt, submittedAt, reviewDeadlineAt, overdueMarkedAt, version + 1,
                createdAt, now);
    }

    /** Заявка в очереди модератора: она не черновик, который заявитель ещё не отправлял (после отказа и правки профиль снова черновик). */
    public boolean visibleToModerator() {
        return status != SellerStatus.DRAFT && submittedAt != null;
    }

    /** Срок рассмотрения вышел, а заявка всё ещё на проверке (признак {@code overdue} в очереди; пометка задания здесь не важна). */
    public boolean pastDeadline(Instant now) {
        return status == SellerStatus.ON_REVIEW && reviewDeadlineAt != null && !now.isBefore(reviewDeadlineAt);
    }

    /** Когда после отказа можно подать новую заявку: отказ плюс пауза. Пусто, если отказа не было. */
    public Optional<Instant> nextApplicationAt(Duration pause) {
        return Optional.ofNullable(lastRejectedAt).map(at -> at.plus(pause));
    }

    /** Пауза идёт: с отказа прошло меньше {@code pause}. Ровно через {@code pause} подать можно ("не раньше чем через 24 часа"). */
    public boolean pauseActive(Instant now, Duration pause) {
        return nextApplicationAt(pause).map(now::isBefore).orElse(false);
    }

    /** Дата отказа показывается, пока отказ ещё «действует» для заявителя: после отказа и в исправляемом черновике. */
    public Optional<Instant> shownRejectedAt() {
        return status == SellerStatus.REJECTED || status == SellerStatus.DRAFT ? Optional.ofNullable(lastRejectedAt) : Optional.empty();
    }

    /** Заявка просрочена: на проверке, срок вышел, пометки ещё нет. */
    public boolean overdue(Instant now) {
        return status == SellerStatus.ON_REVIEW && overdueMarkedAt == null && reviewDeadlineAt != null && !now.isBefore(reviewDeadlineAt);
    }

    public ProblemException stateConflict() {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("currentStatus", status.code());
        return new ProblemException(ProblemType.STATE_CONFLICT, "Действие недоступно в текущем статусе заявки.", extensions, Map.of());
    }

    private ProblemException pauseActiveProblem(Instant now, Duration pause) {
        Instant next = nextApplicationAt(pause).orElseThrow();
        long seconds = Math.max(1, Duration.between(now, next).plusMillis(999).toSeconds());
        return new ProblemException(ProblemType.APPLICATION_PAUSE_ACTIVE, "Повторная заявка пока недоступна, пауза после отказа не закончилась.",
                Map.of("retryAfterAt", Timestamps.format(next)), Map.of("Retry-After", Long.toString(seconds)));
    }

    /** {@code validation-failed} со списком нарушений. */
    public static ProblemException validation(List<FieldViolation> violations) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (FieldViolation v : violations) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("pointer", v.pointer());
            error.put("code", v.code());
            error.put("detail", v.detail());
            errors.add(error);
        }
        return new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                Map.of("errors", List.copyOf(errors)), Map.of());
    }
}
