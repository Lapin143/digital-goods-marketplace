package dgm.platform.identity.otp.service;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.api.CodeIssued;
import dgm.platform.identity.api.EmailChangeCodes;
import dgm.platform.identity.otp.domain.OtpPurpose;
import dgm.platform.identity.otp.domain.PhoneNumber;
import dgm.platform.identity.repository.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link EmailChangeCodes} поверх {@link OtpService}: номер берётся у учётной записи по идентификатору и дальше не выходит из модуля, SMS
 * уходит через {@link SmsSender}, письмо с кодом через {@link EmailCodeSender}. Порядок выпуска: допуск по лимитам (расходует их), затем
 * запись кода и доставка. Соединение с базой удерживается только на время одной выборки номера.
 */
@Component
public final class EmailChangeCodeService implements EmailChangeCodes {

    private final UserAccountRepository accounts;
    private final OtpService otp;
    private final SmsSender sms;
    private final EmailCodeSender mail;

    public EmailChangeCodeService(UserAccountRepository accounts, OtpService otp, SmsSender sms, EmailCodeSender mail) {
        this.accounts = accounts;
        this.otp = otp;
        this.sms = sms;
        this.mail = mail;
    }

    @Override
    public boolean hasConfirmedPhone(UUID userId) {
        return confirmedPhone(userId).isPresent();
    }

    @Override
    public CodeIssued sendPhoneCode(UUID userId, UUID ticketId, Optional<String> ip) {
        PhoneNumber phone = confirmedPhone(userId).orElseThrow(() -> new ProblemException(ProblemType.STATE_CONFLICT,
                "У покупателя нет подтверждённого телефона, смена e-mail недоступна."));
        OtpStore.Admission admission = otp.admitEmailChangePhone(userId, phone, ip);
        OtpService.Issued issued = otp.issueEmailChange(OtpPurpose.EMAIL_CHANGE_PHONE, userId, ticketId, admission,
                code -> sms.send(phone, OtpService.smsText(code)));
        return new CodeIssued(issued.expiresAt(), issued.attempts(), issued.resendAt());
    }

    @Override
    public void verifyPhoneCode(UUID userId, UUID ticketId, String code) {
        otp.verifyEmailChange(OtpPurpose.EMAIL_CHANGE_PHONE, ticketId, code);
    }

    @Override
    public CodeIssued sendNewEmailCode(UUID userId, UUID ticketId, String newEmail, Optional<String> ip) {
        OtpStore.Admission admission = otp.admitEmailChangeNewEmail(userId, newEmail, ip);
        OtpService.Issued issued = otp.issueEmailChange(OtpPurpose.EMAIL_CHANGE_NEW_EMAIL, userId, ticketId, admission,
                code -> mail.send(newEmail, code));
        return new CodeIssued(issued.expiresAt(), issued.attempts(), issued.resendAt());
    }

    @Override
    public void verifyNewEmailCode(UUID userId, UUID ticketId, String code) {
        otp.verifyEmailChange(OtpPurpose.EMAIL_CHANGE_NEW_EMAIL, ticketId, code);
    }

    private Optional<PhoneNumber> confirmedPhone(UUID userId) {
        return accounts.confirmedPhone(userId).flatMap(PhoneNumber::parse);
    }
}
