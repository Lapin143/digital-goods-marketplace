package dgm.delivery.delivery.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Исход одной попытки отправки (SM-06, ADR-011). Код ошибки это короткий идентификатор по маске таблицы {@code delivery_attempt}
 * ({@code ^[a-z0-9_.-]{1,64}$}): значение ключа или текст письма в него поместиться не могут.
 */
public sealed interface AttemptResult {

    /** Маска кода ошибки, как в ограничениях {@code ck_delivery_attempt_error_code} и {@code ck_delivery_last_error_code}. */
    Pattern CODE = Pattern.compile("^[a-z0-9_.-]{1,64}$");

    /** Провайдер принял письмо (T2). */
    record Accepted() implements AttemptResult {
    }

    /** Временная ошибка: тайм-аут, 5xx, 429, сосед недоступен. Попытка потрачена, следующая по расписанию (T3) или «ошибка» после шестой (T4). */
    record Retryable(String code) implements AttemptResult {

        public Retryable {
            AttemptResult.requireCode(code);
        }
    }

    /** Окончательная ошибка: отказ по адресу (400, 422). Повторов нет, сразу «ошибка» (T4). */
    record Permanent(String code) implements AttemptResult {

        public Permanent {
            AttemptResult.requireCode(code);
        }
    }

    private static void requireCode(String code) {
        Objects.requireNonNull(code, "code");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Код ошибки должен подходить под маску " + CODE.pattern());
        }
    }
}
