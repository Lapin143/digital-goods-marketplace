package dgm.keycloak.smsotp.core;

/**
 * Решение «отправлять ли письмо» для шагов, которые отвечают гостю одинаково при любом адресе: регистрация и сброс пароля (F4-1). Перед
 * отправкой шаг занимает место в общем счётчике писем на адрес в платформе. Отказ счётчика ответа не меняет: страница остаётся нейтральной
 * («если адрес ваш, письмо отправлено»), письмо просто не уходит, а время ответа выравнивает {@link TimePad}. Иначе по отказу можно было бы
 * узнавать, что на адрес недавно уже отправляли письмо, то есть что он занят.
 */
public final class EmailQuotaGate {

    /** Что делать с письмом. */
    public enum Verdict {
        /** Место в счётчике занято, письмо отправляется. */
        SEND,
        /** Лимит адреса исчерпан: письма нет, ответ прежний. */
        WITHHOLD_LIMITED,
        /** Платформа или Redis недоступны: письма нет (режим отказа, ADR-010), ответ прежний. */
        WITHHOLD_UNAVAILABLE
    }

    private final PlatformOtp platform;

    public EmailQuotaGate(PlatformOtp platform) {
        this.platform = platform;
    }

    /**
     * @param purpose {@code register} или {@code reset}
     * @param rawEmail адрес как введён гостем: приводится к нижнему регистру; не похожий на адрес в счётчик не попадает и письма не получает
     */
    public Verdict check(String purpose, String rawEmail) {
        var email = RegistrationInput.platformEmail(rawEmail);
        if (email.isEmpty()) {
            return Verdict.WITHHOLD_UNAVAILABLE;
        }
        return switch (platform.consumeQuota(purpose, email.get())) {
            case PlatformOtp.Quota.Granted granted -> Verdict.SEND;
            case PlatformOtp.Quota.Limited limited -> Verdict.WITHHOLD_LIMITED;
            case PlatformOtp.Quota.Unavailable unavailable -> Verdict.WITHHOLD_UNAVAILABLE;
        };
    }
}
