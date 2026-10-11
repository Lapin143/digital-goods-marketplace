package dgm.platform.identity.otp.service;

import java.time.Instant;

/**
 * Выравнивание времени ответа (ST-06): ветка запроса кода без отправки SMS ждёт до той же границы, что ветка с отправкой, поэтому
 * по времени ответа нельзя понять, привязан ли номер. В работе ждёт по часам сервиса, в тестах только запоминает границу.
 */
@FunctionalInterface
public interface ResponsePacer {

    /** Возвращается, когда часы дошли до {@code deadline}; если момент уже прошёл, не ждёт. */
    void awaitUntil(Instant deadline);
}
