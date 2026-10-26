package dgm.platform.support.repository;

import dgm.platform.support.domain.EmailChange;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Этапы смены e-mail по обращению (таблица {@code support.email_change}, Р-169). Методы изменения вызываются внутри транзакции модуля
 * {@code support}; каждый меняет строку одним условным {@code UPDATE ... WHERE status = ...}, поэтому из двух одновременных запросов этап
 * проходит один (правило 15).
 */
public interface EmailChangeRepository {

    Optional<EmailChange> find(UUID ticketId);

    /**
     * Начинает смену: записывает этап «ждём код из SMS» с новым адресом и его маской. Повторная попытка заменяет прежнюю запись обращения, кроме
     * двух случаев: смена завершена или оба подтверждения уже получены и смена ждёт завершения (подтверждённое нельзя выбросить заменой).
     *
     * @return {@code false}: запись не изменена по одной из этих причин
     */
    boolean start(UUID ticketId, UUID userId, String newEmail, String newEmailMasked, Instant at);

    /**
     * Проверка телефона пройдена: этап «ждём код из письма», проверка нового адреса «ожидает».
     *
     * @return {@code false}: этап уже не «ждём код из SMS»
     */
    boolean phonePassed(UUID ticketId, Instant at);

    /**
     * Проверка нового адреса пройдена: этап остаётся «ждём код из письма», результат проверки «пройдена» (оба подтверждения получены, смена не
     * завершена).
     *
     * @return {@code false}: этап уже не «ждём код из письма» или проверка не «ожидает»
     */
    boolean newEmailPassed(UUID ticketId, Instant at);

    /**
     * Смена отклонена: статус «не выполнена», результаты проверок как заданы, новый адрес обнуляется (INV-44, {@code ck_email_change_pii_cleanup}).
     * Подтверждённую и не завершённую смену отклонять нельзя.
     *
     * @param phoneCheck    результат проверки телефона: {@code failed} или {@code passed}
     * @param newEmailCheck результат проверки нового адреса: {@code not_started} или {@code failed}
     * @return {@code false}: смена уже не ждёт кода
     */
    boolean fail(UUID ticketId, String phoneCheck, String newEmailCheck, Instant at);
}
