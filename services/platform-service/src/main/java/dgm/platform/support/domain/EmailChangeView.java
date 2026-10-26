package dgm.platform.support.domain;

import java.time.Instant;

/**
 * Состояние смены e-mail для ответа (схема {@code EmailChangeState}): этап, результат каждой проверки и новый адрес только с маской. Кодов,
 * полного адреса и счётчика попыток здесь нет (FT-7.5, INV-23).
 *
 * @param status          {@code awaiting_phone_code}, {@code awaiting_email_code}, {@code completed}, {@code failed}
 * @param phoneCheck      проверка кода из SMS: {@code pending}, {@code passed}, {@code failed}
 * @param newEmailCheck   проверка кода из письма: {@code not_started}, {@code pending}, {@code passed}, {@code failed}
 * @param newEmailMasked  новый адрес с маской, например {@code n***@mail.example}
 * @param startedAt       когда смена начата
 * @param updatedAt       последнее изменение этапа
 */
public record EmailChangeView(String status, String phoneCheck, String newEmailCheck, String newEmailMasked, Instant startedAt, Instant updatedAt) {

    /** В журнал и сообщения идёт только этап: маска остаётся персональным значением. */
    @Override
    public String toString() {
        return "EmailChangeView[" + status + "]";
    }
}
