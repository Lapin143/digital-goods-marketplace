package dgm.platform.support.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Этап смены e-mail по обращению (таблица {@code support.email_change}, SEQ-04, Р-169): одна запись на обращение, повторная попытка заменяет
 * её. Новый адрес лежит здесь, пока смена не завершена и не отклонена, потом остаётся только маска (INV-44, {@code ck_email_change_pii_cleanup}).
 * Состояние «адрес подтверждён, смена не завершена» ({@link #confirmedNotCompleted()}) это точка продолжения после падения: коды заново не
 * запрашиваются (SEQ-04, раздел 7).
 *
 * @param ticketId       обращение
 * @param userId         покупатель, чей адрес меняется
 * @param newEmail       новый адрес в нормализованном виде; пусто у {@code completed} и {@code failed}
 * @param newEmailMasked новый адрес с маской для показа
 * @param phoneCheck     {@code pending}, {@code passed}, {@code failed}
 * @param newEmailCheck  {@code not_started}, {@code pending}, {@code passed}, {@code failed}
 * @param status         {@code awaiting_phone_code}, {@code awaiting_email_code}, {@code completed}, {@code failed}
 * @param startedAt      когда начата
 * @param updatedAt      последнее изменение
 */
public record EmailChange(UUID ticketId, UUID userId, Optional<String> newEmail, String newEmailMasked, String phoneCheck, String newEmailCheck,
                          String status, Instant startedAt, Instant updatedAt) {

    public static final String AWAITING_PHONE_CODE = "awaiting_phone_code";
    public static final String AWAITING_EMAIL_CODE = "awaiting_email_code";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String PENDING = "pending";
    public static final String PASSED = "passed";
    public static final String NOT_STARTED = "not_started";

    /** Оба подтверждения получены, но смена не завершена: повторять нужно завершение, а не коды. */
    public boolean confirmedNotCompleted() {
        return AWAITING_EMAIL_CODE.equals(status) && PASSED.equals(newEmailCheck);
    }

    public EmailChangeView view() {
        return new EmailChangeView(status, phoneCheck, newEmailCheck, newEmailMasked, startedAt, updatedAt);
    }

    /**
     * Адрес с маской для оператора: первый знак имени, три звёздочки и домен целиком, например {@code n***@mail.example}.
     *
     * @param email нормализованный адрес с одним знаком «@»
     */
    public static String mask(String email) {
        int at = email.indexOf('@');
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    /** Только идентификатор обращения и этап: адрес и маска в журнал не попадают. */
    @Override
    public String toString() {
        return "EmailChange[" + ticketId + ", " + status + "]";
    }
}
