package dgm.keycloak.smsotp.core;

import java.util.function.Predicate;

/**
 * Порядок диалога «адрес, затем код из письма» для гостя, у которого VK ID подтвердил вход, но не передал e-mail (Р-194, F4-3, US-1.2,
 * сценарий 4). Без привязки к Keycloak, как {@link SmsLogin}: адаптер ({@code VkEmailAuthenticator}) только переводит шаг в страницу
 * и обратно, поэтому правила нейтральности проверяются обычными модульными тестами.
 *
 * <p>Правила шага:
 * <ul>
 *   <li>Код уходит на любой корректный адрес. Платформа не ищет учётную запись по адресу и отвечает одинаково, поэтому по странице и по
 *       времени ответа занятый адрес не отличить от свободного (ST-06).</li>
 *   <li>Занятость адреса проверяется только после верного кода, то есть когда гость доказал, что адрес его. Если адрес занят существующей
 *       учётной записью, привязки нет: подтверждённый VK ID не должен превращаться в способ войти в чужую запись без пароля. Гость видит
 *       нейтральное «Не удалось завершить вход, обратитесь в поддержку» ({@link Step.Denied}).</li>
 *   <li>Число писем на адрес ограничивает платформа: место в общем счётчике она занимает сама, при исчерпании отвечает 429.</li>
 * </ul>
 */
public final class VkEmailLogin {

    /** Сообщение для гостя: ключ в файле сообщений темы и число секунд, если оно подставляется в текст. */
    public record Message(String key, long seconds) {

        public static final Message EMAIL_INVALID = new Message("vkEmailInvalid", 0);
        public static final Message CODE_FORMAT = new Message("vkEmailCodeFormat", 0);
        public static final Message CODE_INVALID = new Message("vkEmailCodeInvalid", 0);
        public static final Message CODE_EXPIRED = new Message("vkEmailCodeExpired", 0);
        public static final Message UNAVAILABLE = new Message("vkEmailUnavailable", 0);

        /** Сколько минут ждать, округлено вверх; не меньше одной: страница пишет «повторите через N мин.». */
        public long minutes() {
            return Math.max(1, (seconds + 59) / 60);
        }

        public static Message limited(long seconds) {
            return new Message("vkEmailLimited", seconds);
        }

        public static Message locked(long seconds) {
            return new Message("vkEmailLocked", seconds);
        }
    }

    /** Следующий шаг диалога. */
    public sealed interface Step permits Step.AskEmail, Step.AskCode, Step.Done, Step.Denied {

        /** Показать форму адреса; {@code message} пуст ({@code null}) только на первом показе. */
        record AskEmail(Message message) implements Step {
        }

        /** Показать форму кода для адреса {@code email}. */
        record AskCode(String email, Message message) implements Step {
        }

        /** Адрес подтверждён и свободен: записать его в данные входа и продолжить создание учётной записи. */
        record Done(String email) implements Step {
        }

        /** Адрес подтверждён, но занят существующей учётной записью: привязки нет, вход не завершается. */
        record Denied() implements Step {
        }
    }

    private final PlatformOtp platform;
    private final Predicate<String> taken;

    /**
     * @param platform платформа: коды и счётчик
     * @param taken    проверка занятости адреса в Keycloak (адрес в нижнем регистре); вызывается только после верного кода
     */
    public VkEmailLogin(PlatformOtp platform, Predicate<String> taken) {
        this.platform = platform;
        this.taken = taken;
    }

    /** Гость ввёл адрес. Любой корректный адрес ведёт к форме кода, занят он или нет. */
    public Step submitEmail(String rawEmail) {
        var email = RegistrationInput.platformEmail(rawEmail);
        if (email.isEmpty()) {
            return new Step.AskEmail(Message.EMAIL_INVALID);
        }
        return switch (platform.issueEmail(email.get())) {
            case PlatformOtp.Issued.Accepted accepted -> new Step.AskCode(email.get(), null);
            case PlatformOtp.Issued.Limited limited -> new Step.AskEmail(Message.limited(limited.retryAfterSeconds()));
            case PlatformOtp.Issued.Unavailable unavailable -> new Step.AskEmail(Message.UNAVAILABLE);
        };
    }

    /** Гость попросил новый код для того же адреса. */
    public Step resend(String email) {
        return switch (platform.issueEmail(email)) {
            case PlatformOtp.Issued.Accepted accepted -> new Step.AskCode(email, null);
            case PlatformOtp.Issued.Limited limited -> new Step.AskCode(email, Message.limited(limited.retryAfterSeconds()));
            case PlatformOtp.Issued.Unavailable unavailable -> new Step.AskCode(email, Message.UNAVAILABLE);
        };
    }

    /** Гость ввёл код. Запись неверного формата отклоняется локально и попытку не тратит. */
    public Step submitCode(String email, String rawCode) {
        var code = PhoneInput.normalizeCode(rawCode);
        if (code.isEmpty()) {
            return new Step.AskCode(email, Message.CODE_FORMAT);
        }
        return switch (platform.verifyEmail(email, code.get())) {
            case PlatformOtp.EmailChecked.Confirmed confirmed -> taken.test(email) ? new Step.Denied() : new Step.Done(email);
            case PlatformOtp.EmailChecked.Invalid invalid -> new Step.AskCode(email, Message.CODE_INVALID);
            case PlatformOtp.EmailChecked.Expired expired -> new Step.AskEmail(Message.CODE_EXPIRED);
            case PlatformOtp.EmailChecked.Locked locked -> new Step.AskEmail(Message.locked(locked.retryAfterSeconds()));
            case PlatformOtp.EmailChecked.Unavailable unavailable -> new Step.AskCode(email, Message.UNAVAILABLE);
        };
    }
}
