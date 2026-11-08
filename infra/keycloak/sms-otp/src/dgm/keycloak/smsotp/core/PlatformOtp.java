package dgm.keycloak.smsotp.core;

/**
 * Внутренний интерфейс платформы для кодов входа и счётчика писем (OpenAPI {@code platform-service}: {@code issueOtpCode},
 * {@code verifyOtpCode}, {@code consumeEmailQuota}). Расширение Keycloak ничего не знает о кодах: ни не хранит, ни не проверяет, только
 * передаёт номер или адрес и введённый код и разбирает ответ (ADR-010, вариант 4). Реализация по HTTP: {@link HttpPlatformOtp}.
 */
public interface PlatformOtp {

    /** Результат запроса кода. Ответ платформы одинаков для привязанного и неизвестного номера: различать нечего. */
    sealed interface Issued permits Issued.Accepted, Issued.Limited, Issued.Unavailable {

        /** Запрос принят: «если номер привязан, код отправлен». */
        record Accepted() implements Issued {
        }

        /** Лимит запросов исчерпан или номер закрыт (429). */
        record Limited(long retryAfterSeconds) implements Issued {
        }

        /** Платформа или Redis недоступны, ответ не разобран, соединение не состоялось. */
        record Unavailable() implements Issued {
        }
    }

    /** Результат проверки кода. */
    sealed interface Verified permits Verified.Accepted, Verified.Invalid, Verified.Expired, Verified.Locked, Verified.Unavailable {

        /** Код верен: платформа вернула идентификатор учётной записи. */
        record Accepted(String userId) implements Verified {
        }

        /** Код неверен, либо у номера нет учётной записи: платформа этого не различает. */
        record Invalid() implements Verified {
        }

        /** Срок кода истёк или кода нет (в том числе после исчерпания попыток). */
        record Expired() implements Verified {
        }

        /** Попытки кончились или номер закрыт (429). */
        record Locked(long retryAfterSeconds) implements Verified {
        }

        record Unavailable() implements Verified {
        }
    }

    /** Результат проверки кода из письма (R2, Р-194): адрес подтверждён, учётной записи у кода нет, поэтому идентификатора пользователя нет. */
    sealed interface EmailChecked permits EmailChecked.Confirmed, EmailChecked.Invalid, EmailChecked.Expired, EmailChecked.Locked,
            EmailChecked.Unavailable {

        /** Код верен: введённый адрес принадлежит гостю. */
        record Confirmed() implements EmailChecked {
        }

        /** Код неверен или для этого адреса его не запрашивали: платформа этого не различает. */
        record Invalid() implements EmailChecked {
        }

        /** Срок кода истёк или кода нет (в том числе после исчерпания попыток). */
        record Expired() implements EmailChecked {
        }

        /** Попытки кончились (429). */
        record Locked(long retryAfterSeconds) implements EmailChecked {
        }

        record Unavailable() implements EmailChecked {
        }
    }

    /** Ответ счётчика писем по адресу (F4-1): письмо можно отправлять или нельзя. */
    sealed interface Quota permits Quota.Granted, Quota.Limited, Quota.Unavailable {

        /** Место в счётчике занято, письмо можно отправлять. */
        record Granted() implements Quota {
        }

        /** Лимит адреса исчерпан (429): три письма за 10 минут или десять за сутки. */
        record Limited(long retryAfterSeconds) implements Quota {
        }

        /** Платформа или Redis недоступны (режим отказа, ADR-010), ответ не разобран, соединение не состоялось. */
        record Unavailable() implements Quota {
        }
    }

    /**
     * Просит платформу выслать код входа.
     *
     * @param phone    номер в E.164
     * @param clientIp адрес клиента для лимитов или {@code null}, если неизвестен
     */
    Issued issue(String phone, String clientIp);

    /**
     * Просит платформу выслать код на адрес e-mail при входе через VK ID без адреса (назначение {@code vk_email}). Место в счётчике писем
     * платформа занимает сама, отдельно {@link #consumeQuota} для этого шага не вызывается.
     *
     * @param email адрес в нижнем регистре, прошедший проверку формы
     */
    Issued issueEmail(String email);

    /**
     * Проверяет код из письма.
     *
     * @param email адрес, на который код запрашивался
     * @param code  шесть цифр
     */
    EmailChecked verifyEmail(String email, String code);

    /**
     * Занимает место в общем счётчике писем на адрес перед отправкой письма Keycloak (регистрация, сброс пароля).
     *
     * @param purpose {@code register} или {@code reset}
     * @param email   адрес в нижнем регистре
     */
    Quota consumeQuota(String purpose, String email);

    /**
     * Проверяет код входа.
     *
     * @param phone номер в E.164
     * @param code  шесть цифр
     */
    Verified verify(String phone, String code);
}
