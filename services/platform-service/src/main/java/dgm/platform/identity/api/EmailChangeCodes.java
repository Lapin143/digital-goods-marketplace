package dgm.platform.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Одноразовые коды смены e-mail по обращению для других модулей платформы (подпакет {@code api}, правило модульности 1, Р-169). Модуль
 * поддержки ведёт этапы и решает, когда какой код нужен, а выпуск, лимиты, доставку и неделимую проверку делает identity: коды и номер
 * телефона из этого интерфейса наружу не выходят. Код привязан к обращению ({@code ticketId}): код одного обращения к другому не подходит.
 * Запись кода живёт в Redis только как HMAC, срок 5 минут, 5 попыток; при недоступном Redis любой выпуск и проверка отвечают 503.
 *
 * <p>Все методы бросают {@link dgm.kit.problem.ProblemException}: 429 {@code otp-request-limited} при исчерпанном лимите запросов, 503, если
 * Redis или провайдер недоступны; проверки дают 422 {@code otp-invalid} (с {@code attemptsLeft}), 422 {@code otp-expired}, 429
 * {@code otp-locked} после пятой неудачи.
 */
public interface EmailChangeCodes {

    /** @return у активной учётной записи есть подтверждённый телефон; без него смена e-mail невозможна (INV-35) */
    boolean hasConfirmedPhone(UUID userId);

    /** Выпускает код и отправляет SMS на подтверждённый телефон учётной записи (SEQ-04, шаги 4 – 7). */
    CodeIssued sendPhoneCode(UUID userId, UUID ticketId, Optional<String> ip);

    /** Проверяет код из SMS и гасит его (SEQ-04, шаги 9 – 10). */
    void verifyPhoneCode(UUID userId, UUID ticketId, String code);

    /** Выпускает код и отправляет письмо на новый адрес напрямую, минуя очередь уведомлений (SEQ-04, шаги 12 – 13). */
    CodeIssued sendNewEmailCode(UUID userId, UUID ticketId, String newEmail, Optional<String> ip);

    /** Проверяет код из письма и гасит его (SEQ-04, шаги 15 – 16). */
    void verifyNewEmailCode(UUID userId, UUID ticketId, String code);
}
