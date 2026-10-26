package dgm.platform.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Применение подтверждённой смены e-mail для других модулей платформы (подпакет {@code api}, правило модульности 1, Р-169). Поддержка ведёт этапы и
 * решает, когда оба подтверждения получены, а изменение учётной записи, Keycloak, событие и запись аудита делает identity: хеши адресов считаются
 * здесь, с солью пользователя, а адреса наружу не выходят (ADR-014, INV-44).
 */
public interface EmailChanger {

    /**
     * Меняет адрес учётной записи: сначала в Keycloak (Admin REST, идемпотентно), затем одной транзакцией в {@code identity}: новый адрес в записи,
     * прежний в {@code previous_email} на 7 суток, событие {@code user.email-changed} и запись аудита {@code support.email_change_completed} с хешами
     * адреса до и после. Вызов идемпотентен: если адрес уже стоит, ничего не меняется и событие не повторяется.
     *
     * @param userId     пользователь, чей адрес меняется
     * @param newEmail   новый адрес в нормализованном виде
     * @param ticketId   обращение, по которому идёт смена
     * @param operatorId оператор, принявший решение; пусто, если обращение уже вернули в очередь (исполнитель в журнале «система»)
     * @return {@code true}: адрес изменён этим вызовом; {@code false}: он уже стоял
     * @throws dgm.kit.problem.ProblemException 409 {@code email-not-allowed}: адрес успели занять (у платформы или в Keycloak); 409
     *                                          {@code state-conflict}: у пользователя нет активной учётной записи; 503: Keycloak недоступен,
     *                                          вызов можно повторить
     */
    boolean apply(UUID userId, String newEmail, UUID ticketId, Optional<UUID> operatorId);
}
