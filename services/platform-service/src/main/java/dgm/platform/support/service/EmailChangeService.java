package dgm.platform.support.service;

import dgm.kit.outbox.AuditRecords;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.api.AccountEmails;
import dgm.platform.identity.api.EmailChangeCodes;
import dgm.platform.identity.api.EmailChanger;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.support.domain.EmailChange;
import dgm.platform.support.domain.EmailChangeView;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.domain.SupportTicket;
import dgm.platform.support.repository.EmailChangeRepository;
import dgm.platform.support.repository.SupportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Смена e-mail по обращению ({@code startEmailChange}, {@code getEmailChangeState}, {@code requestEmailChangeCode}, {@code verifyEmailChangeCode};
 * OP-73, OP-74, US-7.4, SEQ-04, Р-169). Оператор называет новый адрес и видит только результат проверок; коды вводит сам покупатель. После верного кода из
 * письма смена в состоянии «адрес подтверждён, не завершён» ({@link EmailChange#confirmedNotCompleted()}) и сразу завершается ({@link #complete}): адрес
 * меняет identity ({@link EmailChanger}: Keycloak, запись, событие {@code user.email-changed}, аудит с хешами), затем в транзакции {@code support} смена
 * получает статус «выполнена», а повторная отправка ключа на новый адрес запускается сама (BPMN-04, решение 3). Две базы под разными ролями одной транзакцией
 * не охватить, поэтому шаги идемпотентны, а то, что не завершилось сразу, доводит сверка ({@link EmailChangeReconciler}): коды при этом заново не
 * запрашиваются (SEQ-04, раздел 7).
 *
 * <p>Этапы: {@code awaiting_phone_code} (SMS на подтверждённый телефон) → {@code awaiting_email_code} (письмо на новый адрес) → {@code completed};
 * любая исчерпанная проверка и занятый адрес дают {@code failed}, адрес обнуляется, обращение остаётся в работе, оператор может начать смену заново.
 *
 * <p>Порядок в каждой операции (правило 9: вызов внешней системы не внутри транзакции, Р-165: решение оператора записывается до действия): проверки по
 * базе без блокировок, короткая транзакция с этапом (и записью аудита у {@code startEmailChange}), затем выпуск кода и доставка. Коды и их лимиты
 * ведёт модуль {@code identity} ({@link EmailChangeCodes}), здесь они не видны. Уникальность нового адреса проверяется дважды: до первого кода и
 * после подтверждения телефона, перед письмом (гонка с регистрацией); код на чужой адрес не уходит никогда (INV-34).
 *
 * <p>Метрика {@code support_email_change_total{operation,result}}: {@code accepted} либо код проблемы ответа, {@code error} для остального. Ни адреса, ни
 * маски, ни кода в журнале, метриках и записях аудита нет.
 */
public final class EmailChangeService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailChangeService.class);

    /** Код действия в журнале аудита: оператор начал смену e-mail (запись до отправки кода). */
    public static final String ACTION_STARTED = "support.email_change_started";
    public static final String METRIC_NAME = "support_email_change_total";
    static final String AUDIT_OBJECT = "ticket";
    private static final String SOURCE_OPERATOR = "operator";

    /** Какой код относится к запросу: из SMS или из письма на новый адрес (поле {@code step}). */
    public enum Step {
        PHONE("phone"),
        NEW_EMAIL("new_email");

        private final String code;

        Step(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Optional<Step> byCode(String code) {
            for (Step step : values()) {
                if (step.code.equals(code)) {
                    return Optional.of(step);
                }
            }
            return Optional.empty();
        }
    }

    private final SupportRepository tickets;
    private final EmailChangeRepository changes;
    private final SupportTransaction transaction;
    private final SupportOutbox outbox;
    private final EmailChangeCodes codes;
    private final AccountEmails emails;
    private final UserContacts contacts;
    private final EmailChanger changer;
    private final ResendService resend;
    private final Clock clock;
    private final MeterRegistry metrics;

    public EmailChangeService(SupportRepository tickets, EmailChangeRepository changes, SupportTransaction transaction, SupportOutbox outbox,
                              EmailChangeCodes codes, AccountEmails emails, UserContacts contacts, EmailChanger changer, ResendService resend, Clock clock,
                              MeterRegistry metrics) {
        this.tickets = tickets;
        this.changes = changes;
        this.transaction = transaction;
        this.outbox = outbox;
        this.codes = codes;
        this.emails = emails;
        this.contacts = contacts;
        this.changer = changer;
        this.resend = resend;
        this.clock = clock;
        this.metrics = metrics;
    }

    // ---- оператор ----

    /**
     * {@code startEmailChange}: начинает смену и отправляет SMS с кодом на подтверждённый телефон покупателя. Повторный вызов заменяет прежнюю попытку
     * этого обращения.
     *
     * @param newEmail новый адрес в нормализованном виде (нижний регистр, без пробелов)
     * @throws ProblemException 404 (нет обращения); 409 ({@code ticket-not-in-progress}, {@code state-conflict}: нет активной учётной записи или
     *                          подтверждённого телефона, смена уже завершена или ждёт завершения; {@code email-not-allowed}: адрес использовать нельзя);
     *                          429 {@code otp-request-limited}; 503
     */
    public EmailChangeView start(Operator operator, UUID ticketId, String newEmail) {
        return counted("start", () -> doStart(operator, ticketId, newEmail));
    }

    /**
     * {@code getEmailChangeState}: результат проверок смены, которую оператор ведёт по своему обращению.
     *
     * @throws ProblemException 404: обращения нет, оно не в работе у этого оператора или смена не начата
     */
    public EmailChangeView state(Operator operator, UUID ticketId) {
        StaffTicket ticket = tickets.findStaffTicket(ticketId).orElseThrow(EmailChangeService::ticketNotFound);
        if (!SupportTicket.IN_PROGRESS.equals(ticket.status()) || ticket.operatorId().filter(operator.id()::equals).isEmpty()) {
            throw new ProblemException(ProblemType.NOT_FOUND, "Смена e-mail по обращению среди ваших обращений в работе не найдена.");
        }
        return changes.find(ticketId).map(EmailChange::view)
                .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND, "Смена e-mail по обращению не начата."));
    }

    // ---- покупатель ----

    /**
     * {@code requestEmailChangeCode}: новый код вместо прежнего в пределах лимитов.
     *
     * @throws ProblemException 404 (чужое или отсутствующее обращение); 409 {@code state-conflict} (смена не начата или код этого этапа сейчас не нужен),
     *                          {@code email-not-allowed} (адрес успели занять); 429 {@code otp-request-limited}; 503
     */
    public EmailChangeView requestCode(UUID buyerId, Optional<String> ip, UUID ticketId, Step step) {
        return counted("request_code", () -> doRequestCode(buyerId, ip, ticketId, step));
    }

    /**
     * {@code verifyEmailChangeCode}: проверка кода покупателя. Верный код из SMS переводит смену к письму и отправляет его; верный код из письма
     * подтверждает адрес и завершает смену. Если завершение не удалось из-за недоступности Keycloak (503 внутри), ответ остаётся успешным: код уже принят
     * и погашен, смена в состоянии «адрес подтверждён, не завершён», её доведёт сверка.
     *
     * @throws ProblemException 404; 409 ({@code state-conflict}, {@code email-not-allowed}: адрес успели занять, смена отклонена); 422
     *                          {@code otp-invalid} (с {@code attemptsLeft}) или {@code otp-expired}; 429 {@code otp-locked} после пятой неудачи (смена
     *                          отклонена) или {@code otp-request-limited} (письмо после телефона); 503
     */
    public EmailChangeView verify(UUID buyerId, Optional<String> ip, UUID ticketId, Step step, String code) {
        return counted("verify_" + step.code(), () -> doVerify(buyerId, ip, ticketId, step, code));
    }

    // ---- ход операций ----

    private EmailChangeView doStart(Operator operator, UUID ticketId, String newEmail) {
        StaffTicket ticket = tickets.findStaffTicket(ticketId).orElseThrow(EmailChangeService::ticketNotFound);
        if (!SupportTicket.IN_PROGRESS.equals(ticket.status()) || ticket.operatorId().filter(operator.id()::equals).isEmpty()) {
            throw new ProblemException(ProblemType.TICKET_NOT_IN_PROGRESS, "Сменить e-mail можно только по обращению, которое вы взяли в работу.");
        }
        UUID buyerId = ticket.buyerId();
        contacts.findActive(buyerId).orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT,
                "У покупателя нет активной учётной записи, адрес сменить нельзя."));
        if (!codes.hasConfirmedPhone(buyerId)) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "У покупателя нет подтверждённого телефона: смена e-mail по обращению недоступна.");
        }
        changes.find(ticketId).ifPresent(EmailChangeService::refuseIfFinal);
        // Нейтральный отказ без причины, код не отправляется (INV-34): адрес занят чужой записью или это адрес самого покупателя
        if (emails.isUnavailable(newEmail)) {
            throw emailNotAllowed();
        }

        Instant startedAt = clock.instant();
        transaction.execute(() -> {
            if (!changes.start(ticketId, buyerId, newEmail, EmailChange.mask(newEmail), startedAt)) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Смена e-mail по обращению уже завершена или ждёт завершения.");
            }
            if (!tickets.markEmailChange(ticketId, operator.id())) {
                throw new ProblemException(ProblemType.TICKET_NOT_IN_PROGRESS, "Обращение вернули в очередь или передали другому оператору.");
            }
            outbox.publish(AuditRecords.TOPIC, AuditRecords.AGGREGATE, ticketId.toString(), AuditRecords.EVENT, AuditRecords.SCHEMA_VERSION,
                    AuditRecords.staffAction(startedAt, operator.id(), Operator.ROLE, operator.ip(), ACTION_STARTED, AUDIT_OBJECT, ticketId.toString(),
                            List.of(AuditRecords.change("action", null, "email_change"),
                                    AuditRecords.change("userId", null, buyerId.toString()),
                                    AuditRecords.change("source", null, SOURCE_OPERATOR))));
            return null;
        });

        codes.sendPhoneCode(buyerId, ticketId, ip(operator.ip()));
        LOG.info("Смена e-mail по обращению {} начата оператором {}, код отправлен на телефон покупателя", ticketId, operator.id());
        return current(ticketId);
    }

    private EmailChangeView doRequestCode(UUID buyerId, Optional<String> ip, UUID ticketId, Step step) {
        EmailChange change = ownChange(buyerId, ticketId);
        switch (step) {
            case PHONE -> {
                requireStage(change, EmailChange.AWAITING_PHONE_CODE, EmailChange.PENDING.equals(change.phoneCheck()));
                codes.sendPhoneCode(buyerId, ticketId, ip);
            }
            case NEW_EMAIL -> {
                requireStage(change, EmailChange.AWAITING_EMAIL_CODE, EmailChange.PENDING.equals(change.newEmailCheck()));
                String newEmail = newEmailOf(change);
                recheckUniqueness(ticketId, newEmail);
                codes.sendNewEmailCode(buyerId, ticketId, newEmail, ip);
            }
        }
        return current(ticketId);
    }

    private EmailChangeView doVerify(UUID buyerId, Optional<String> ip, UUID ticketId, Step step, String code) {
        EmailChange change = ownChange(buyerId, ticketId);
        switch (step) {
            case PHONE -> verifyPhone(buyerId, ip, ticketId, change, code);
            case NEW_EMAIL -> verifyNewEmail(buyerId, ticketId, change, code);
        }
        return current(ticketId);
    }

    private void verifyPhone(UUID buyerId, Optional<String> ip, UUID ticketId, EmailChange change, String code) {
        requireStage(change, EmailChange.AWAITING_PHONE_CODE, EmailChange.PENDING.equals(change.phoneCheck()));
        try {
            codes.verifyPhoneCode(buyerId, ticketId, code);
        } catch (ProblemException e) {
            if (e.type() == ProblemType.OTP_LOCKED) {
                reject(ticketId, EmailChange.FAILED, EmailChange.NOT_STARTED);
            }
            throw e;
        }
        String newEmail = newEmailOf(change);
        // Второй раз: пока шли первые минуты, адрес могли занять другой регистрацией; письмо с кодом на него уйти не должно
        recheckUniqueness(ticketId, newEmail);
        Instant now = clock.instant();
        transaction.execute(() -> {
            if (!changes.phonePassed(ticketId, now)) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Проверка телефона уже выполнена другим запросом.");
            }
            return null;
        });
        codes.sendNewEmailCode(buyerId, ticketId, newEmail, ip);
    }

    private void verifyNewEmail(UUID buyerId, UUID ticketId, EmailChange change, String code) {
        requireStage(change, EmailChange.AWAITING_EMAIL_CODE, EmailChange.PENDING.equals(change.newEmailCheck()));
        try {
            codes.verifyNewEmailCode(buyerId, ticketId, code);
        } catch (ProblemException e) {
            if (e.type() == ProblemType.OTP_LOCKED) {
                reject(ticketId, EmailChange.PASSED, EmailChange.FAILED);
            }
            throw e;
        }
        Instant now = clock.instant();
        transaction.execute(() -> {
            if (!changes.newEmailPassed(ticketId, now)) {
                throw new ProblemException(ProblemType.STATE_CONFLICT, "Проверка нового адреса уже выполнена другим запросом.");
            }
            return null;
        });
        LOG.info("Новый адрес по обращению {} подтверждён кодом из письма, смена ждёт завершения", ticketId);
        try {
            complete(ticketId);
        } catch (ProblemException e) {
            if (e.type() != ProblemType.DEPENDENCY_UNAVAILABLE) {
                throw e;
            }
            LOG.warn("Смена e-mail по обращению {} подтверждена, но не завершена: сервис идентификации недоступен, завершит сверка", ticketId);
        }
    }

    // ---- завершение ----

    /**
     * Завершает подтверждённую смену (SEQ-04, шаги 18 – 23): identity меняет адрес (Keycloak, запись, событие, аудит), затем транзакция {@code support}
     * переводит смену в «выполнена», записывает результат проверки кода у обращения и обнуляет новый адрес, после чего сама запускается повторная отправка
     * ключа. Метод идемпотентен: у завершённой смены возвращает её состояние, а повтор после падения между шагами доделывает недоделанное, не запрашивая коды.
     * Сбой автоповтора смену не откатывает: адрес уже изменён, оператор видит обращение в работе и повторяет отправку сам.
     *
     * @throws ProblemException 409 {@code email-not-allowed} (адрес успели занять) или {@code state-conflict} (учётная запись больше не активна): смена
     *                          отклонена, обращение остаётся в работе; 503: identity недоступна, смена остаётся подтверждённой и не завершённой
     */
    public EmailChangeView complete(UUID ticketId) {
        EmailChange change = changes.find(ticketId).orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT, "Смена e-mail по обращению не начата."));
        if (EmailChange.COMPLETED.equals(change.status())) {
            return change.view();
        }
        if (!change.confirmedNotCompleted()) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Оба подтверждения смены e-mail ещё не получены.");
        }
        Optional<UUID> operatorId = tickets.findStaffTicket(ticketId).flatMap(StaffTicket::operatorId);
        try {
            changer.apply(change.userId(), newEmailOf(change), ticketId, operatorId);
        } catch (ProblemException e) {
            if (e.type() != ProblemType.DEPENDENCY_UNAVAILABLE) {
                Instant failedAt = clock.instant();
                transaction.execute(() -> changes.failConfirmed(ticketId, failedAt));
                LOG.info("Подтверждённая смена e-mail по обращению {} отклонена: {}", ticketId, e.type().code());
            }
            count("complete", e.type().code());
            throw e;
        } catch (RuntimeException e) {
            count("complete", "error");
            throw e;
        }
        Instant now = clock.instant();
        boolean finished = transaction.execute(() -> {
            boolean done = changes.completed(ticketId, now);
            tickets.markCodeCheckPassed(ticketId);
            return done;
        });
        count("complete", "accepted");
        LOG.info("Смена e-mail по обращению {} завершена", ticketId);
        if (finished) {
            autoResend(ticketId);
        }
        return current(ticketId);
    }

    /**
     * Сверка (SEQ-04, раздел 7): доводит до конца подтверждённые смены, которые не менялись с момента {@code before}. Падение платформы между Keycloak и
     * завершением, недоступность identity при подтверждении кода и сбой базы между шагами выглядят одинаково: этап подтверждён, не завершён.
     *
     * @return сколько смен завершено; ошибка по одной смене проход не останавливает
     */
    public int finishStuck(Instant before, int limit) {
        int finished = 0;
        for (UUID ticketId : changes.confirmedNotCompletedSince(before, limit)) {
            try {
                complete(ticketId);
                finished++;
            } catch (ProblemException e) {
                LOG.warn("Сверка не завершила смену e-mail по обращению {}: {}", ticketId, e.type().code());
            } catch (RuntimeException e) {
                LOG.warn("Сверка не завершила смену e-mail по обращению {}: {}", ticketId, e.getClass().getSimpleName());
            }
        }
        return finished;
    }

    /** Повторная отправка ключа на новый адрес после смены: отдельный шаг, чей сбой смену не откатывает. */
    private void autoResend(UUID ticketId) {
        try {
            if (resend.resendAfterEmailChange(ticketId)) {
                count("resend", "accepted");
            } else {
                count("resend", "skipped");
            }
        } catch (ProblemException e) {
            count("resend", e.type().code());
            LOG.warn("Автоповтор выдачи после смены e-mail по обращению {} не запущен: {}; оператор повторит отправку сам", ticketId, e.type().code());
        } catch (RuntimeException e) {
            count("resend", "error");
            LOG.warn("Автоповтор выдачи после смены e-mail по обращению {} не запущен: {}", ticketId, e.getClass().getSimpleName());
        }
    }

    // ---- вспомогательное ----

    /** Обращение покупателя в работе и начатая по нему смена, которая ещё не закрыта. */
    private EmailChange ownChange(UUID buyerId, UUID ticketId) {
        SupportTicket ticket = tickets.findTicket(ticketId).filter(t -> t.buyerId().equals(buyerId)).orElseThrow(EmailChangeService::ticketNotFound);
        EmailChange change = changes.find(ticketId).orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT,
                "Смена e-mail по этому обращению не начата."));
        if (!SupportTicket.IN_PROGRESS.equals(ticket.status())) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Обращение не в работе, смена e-mail по нему недоступна.");
        }
        return change;
    }

    /** Этап и проверка должны быть те, что ждёт операция, иначе порядок нарушен (409). */
    private static void requireStage(EmailChange change, String status, boolean checkPending) {
        if (!status.equals(change.status()) || !checkPending) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Этот этап смены e-mail сейчас не ожидается.");
        }
    }

    private static void refuseIfFinal(EmailChange change) {
        if (EmailChange.COMPLETED.equals(change.status()) || change.confirmedNotCompleted()) {
            throw new ProblemException(ProblemType.STATE_CONFLICT, "Смена e-mail по обращению уже завершена или ждёт завершения.");
        }
    }

    private static String newEmailOf(EmailChange change) {
        return change.newEmail().orElseThrow(() -> new IllegalStateException("У этапа смены e-mail нет адреса"));
    }

    /** Занятый к этому моменту адрес: смена отклоняется (адрес обнуляется), ответ тот же нейтральный. */
    private void recheckUniqueness(UUID ticketId, String newEmail) {
        if (emails.isUnavailable(newEmail)) {
            reject(ticketId, EmailChange.PASSED, EmailChange.FAILED);
            throw emailNotAllowed();
        }
    }

    /** Отклонённая смена: статус {@code failed}, адрес обнулён, обращение остаётся в работе. */
    private void reject(UUID ticketId, String phoneCheck, String newEmailCheck) {
        Instant now = clock.instant();
        transaction.execute(() -> changes.fail(ticketId, phoneCheck, newEmailCheck, now));
        LOG.info("Смена e-mail по обращению {} отклонена", ticketId);
    }

    private EmailChangeView current(UUID ticketId) {
        return changes.find(ticketId).map(EmailChange::view).orElseThrow(() -> new IllegalStateException("Записанный этап смены не читается"));
    }

    private static ProblemException emailNotAllowed() {
        return new ProblemException(ProblemType.EMAIL_NOT_ALLOWED, "Адрес использовать нельзя.");
    }

    private static ProblemException ticketNotFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Обращение не найдено.");
    }

    private static Optional<String> ip(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private EmailChangeView counted(String operation, Supplier<EmailChangeView> action) {
        try {
            EmailChangeView view = action.get();
            count(operation, "accepted");
            return view;
        } catch (ProblemException e) {
            count(operation, e.type().code());
            throw e;
        } catch (RuntimeException e) {
            count(operation, "error");
            throw e;
        }
    }

    private void count(String operation, String result) {
        metrics.counter(METRIC_NAME, "operation", operation, "result", result.toLowerCase(Locale.ROOT)).increment();
    }
}
