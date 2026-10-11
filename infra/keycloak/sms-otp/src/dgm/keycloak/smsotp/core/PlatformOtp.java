package dgm.keycloak.smsotp.core;

/**
 * Внутренний интерфейс платформы для входа по SMS (OpenAPI {@code platform-service}: {@code issueOtpCode}, {@code verifyOtpCode}).
 * Расширение Keycloak ничего не знает о кодах: ни не хранит, ни не проверяет, только передаёт номер и введённый код и разбирает ответ
 * (ADR-010, вариант 4). Реализация по HTTP: {@link HttpPlatformOtp}.
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

    /**
     * Просит платформу выслать код входа.
     *
     * @param phone    номер в E.164
     * @param clientIp адрес клиента для лимитов или {@code null}, если неизвестен
     */
    Issued issue(String phone, String clientIp);

    /**
     * Проверяет код входа.
     *
     * @param phone номер в E.164
     * @param code  шесть цифр
     */
    Verified verify(String phone, String code);
}
