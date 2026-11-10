package dgm.platform.identity.otp.service;

import org.springframework.stereotype.Component;

/**
 * Код из письма при входе через VK ID без адреса (Р-194, US-1.2, сценарий 4): две внутренние операции, которые вызывает расширение Keycloak
 * {@code sms-otp} (шаг {@code dgm-vk-email}): {@code issueOtpCode} и {@code verifyOtpCode} с целью {@code vk_email}.
 *
 * <p>Запрос кода нейтрален: письмо уходит на любой названный адрес, занят он или нет, платформа учётные записи не просматривает. Занятость
 * адреса проверяет Keycloak только после верного кода, когда гость доказал, что ящик его (иначе подтверждённый VK ID превратился бы в способ
 * захвата чужой записи без пароля, а ответ выдавал бы, есть ли запись). Счётчик писем на адрес занимается до выпуска отдельной операцией
 * {@code consumeEmailQuota}.
 */
@Component
public final class VkEmailCodeService {

    private final OtpService otp;
    private final EmailCodeSender mail;

    public VkEmailCodeService(OtpService otp, EmailCodeSender mail) {
        this.otp = otp;
        this.mail = mail;
    }

    /**
     * Выпускает код и отправляет письмо.
     *
     * @param email адрес, который назвал гость
     * @throws dgm.kit.problem.ProblemException 429 {@code otp-request-limited}, если на адрес уже ушло три письма за 10 минут или десять за
     *                                          сутки (общий счётчик с регистрацией и сбросом пароля); 503, если провайдер почты не принял
     *                                          письмо или Redis недоступен
     */
    public void requestCode(String email) {
        String address = OtpService.normalizeEmail(email);
        // Место в счётчике писем занимается до выпуска: при отказе (429) код не выпускается, прежний не гасится, письма нет. Контракт
        // обещал это платформе, а расширение для этой цели счётчик не вызывает: без этой строки четвёртое письмо уходило (F5-51)
        otp.consumeEmailQuota("vk_email", address);
        otp.issueEmailLogin(address, code -> mail.sendLoginCode(address, code));
    }

    /**
     * Проверяет код из письма.
     *
     * @throws dgm.kit.problem.ProblemException 422 {@code otp-invalid} / {@code otp-expired}; 429 {@code otp-locked}
     */
    public void verifyCode(String email, String code) {
        otp.verifyEmailLogin(email, code);
    }
}
