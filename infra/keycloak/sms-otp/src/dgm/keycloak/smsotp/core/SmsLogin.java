package dgm.keycloak.smsotp.core;

/**
 * Порядок диалога «номер, затем код» без привязки к Keycloak: что показать после каждого ответа покупателя и платформы. Адаптер
 * ({@code SmsOtpAuthenticator}) только переводит шаг в страницу Keycloak и обратно. Из-за этого правила нейтральности проверяются
 * обычными модульными тестами: для привязанного и неизвестного номера платформа отвечает одинаково, а значит одинаковы и шаги.
 */
public final class SmsLogin {

    /** Сообщение для покупателя: ключ в файле сообщений темы и число секунд, если оно подставляется в текст. */
    public record Message(String key, long seconds) {

        public static final Message PHONE_INVALID = new Message("smsPhoneInvalid", 0);
        public static final Message CODE_FORMAT = new Message("smsCodeFormat", 0);
        public static final Message CODE_INVALID = new Message("smsCodeInvalid", 0);
        public static final Message CODE_EXPIRED = new Message("smsCodeExpired", 0);
        public static final Message UNAVAILABLE = new Message("smsUnavailable", 0);

        /** Сколько минут ждать, округлено вверх; не меньше одной: страница пишет «повторите через N мин.». */
        public long minutes() {
            return Math.max(1, (seconds + 59) / 60);
        }

        public static Message limited(long seconds) {
            return new Message("smsLimited", seconds);
        }

        public static Message locked(long seconds) {
            return new Message("smsLocked", seconds);
        }
    }

    /** Следующий шаг диалога. */
    public sealed interface Step permits Step.AskPhone, Step.AskCode, Step.Done {

        /** Показать форму номера; {@code message} пуст ({@code null}) только на первом показе. */
        record AskPhone(Message message) implements Step {
        }

        /** Показать форму кода для номера {@code phone}. */
        record AskCode(String phone, Message message) implements Step {
        }

        /** Код верен: войти от имени пользователя {@code userId}. */
        record Done(String userId) implements Step {
        }
    }

    private final PlatformOtp platform;

    public SmsLogin(PlatformOtp platform) {
        this.platform = platform;
    }

    /** Покупатель ввёл номер. Любой корректный номер ведёт к форме кода, привязан он или нет. */
    public Step submitPhone(String rawPhone, String clientIp) {
        var phone = PhoneInput.normalize(rawPhone);
        if (phone.isEmpty()) {
            return new Step.AskPhone(Message.PHONE_INVALID);
        }
        return switch (platform.issue(phone.get(), clientIp)) {
            case PlatformOtp.Issued.Accepted accepted -> new Step.AskCode(phone.get(), null);
            case PlatformOtp.Issued.Limited limited -> new Step.AskPhone(Message.limited(limited.retryAfterSeconds()));
            case PlatformOtp.Issued.Unavailable unavailable -> new Step.AskPhone(Message.UNAVAILABLE);
        };
    }

    /** Покупатель попросил новый код для того же номера. */
    public Step resend(String phone, String clientIp) {
        return switch (platform.issue(phone, clientIp)) {
            case PlatformOtp.Issued.Accepted accepted -> new Step.AskCode(phone, null);
            case PlatformOtp.Issued.Limited limited -> new Step.AskCode(phone, Message.limited(limited.retryAfterSeconds()));
            case PlatformOtp.Issued.Unavailable unavailable -> new Step.AskCode(phone, Message.UNAVAILABLE);
        };
    }

    /** Покупатель ввёл код. Запись неверного формата отклоняется локально и попытку не тратит. */
    public Step submitCode(String phone, String rawCode) {
        var code = PhoneInput.normalizeCode(rawCode);
        if (code.isEmpty()) {
            return new Step.AskCode(phone, Message.CODE_FORMAT);
        }
        return switch (platform.verify(phone, code.get())) {
            case PlatformOtp.Verified.Accepted accepted -> new Step.Done(accepted.userId());
            case PlatformOtp.Verified.Invalid invalid -> new Step.AskCode(phone, Message.CODE_INVALID);
            case PlatformOtp.Verified.Expired expired -> new Step.AskPhone(Message.CODE_EXPIRED);
            case PlatformOtp.Verified.Locked locked -> new Step.AskPhone(Message.locked(locked.retryAfterSeconds()));
            case PlatformOtp.Verified.Unavailable unavailable -> new Step.AskCode(phone, Message.UNAVAILABLE);
        };
    }
}
