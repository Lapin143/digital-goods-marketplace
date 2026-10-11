package dgm.platform.identity.otp.domain;

import java.util.Optional;

/**
 * Назначение одноразового кода (ADR-010): код для одного назначения к другому не подходит. В срезе 1 работают два; смена e-mail по
 * обращению и смена номера (R2) добавят остальные.
 */
public enum OtpPurpose {

    /** Подтверждение телефона вошедшим покупателем (FT-1.5). */
    PHONE_CONFIRM("phone_confirm"),
    /** Вход по коду из SMS (FT-1.7). */
    SMS_LOGIN("sms_login");

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
