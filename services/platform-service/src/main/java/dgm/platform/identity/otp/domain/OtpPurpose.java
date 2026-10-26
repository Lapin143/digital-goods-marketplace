package dgm.platform.identity.otp.domain;

import java.util.Optional;

/**
 * Назначение одноразового кода (ADR-010): код для одного назначения к другому не подходит. Подтверждение телефона и вход по SMS работают с
 * среза 1, два кода смены e-mail по обращению добавлены в срезе 3 (Р-169); смена номера (R2) добавит остальные.
 */
public enum OtpPurpose {

    /** Подтверждение телефона вошедшим покупателем (FT-1.5). */
    PHONE_CONFIRM("phone_confirm"),
    /** Вход по коду из SMS (FT-1.7). */
    SMS_LOGIN("sms_login"),
    /** Код из SMS на подтверждённый телефон при смене e-mail по обращению, привязан к обращению (Р-169, SEQ-04, шаг 5). */
    EMAIL_CHANGE_PHONE("email_change_phone"),
    /** Код из письма на новый адрес при смене e-mail по обращению, привязан к обращению (Р-169, SEQ-04, шаг 12). */
    EMAIL_CHANGE_NEW_EMAIL("email_change_new_email");

    private final String code;

    OtpPurpose(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<OtpPurpose> byCode(String code) {
        for (OtpPurpose purpose : values()) {
            if (purpose.code.equals(code)) {
                return Optional.of(purpose);
            }
        }
        return Optional.empty();
    }
}
