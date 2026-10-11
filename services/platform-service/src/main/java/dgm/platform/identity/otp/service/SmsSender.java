package dgm.platform.identity.otp.service;

import dgm.platform.identity.otp.domain.PhoneNumber;

/** Отправка SMS провайдеру (компонент {@code sms-client}). Ошибки провайдера превращаются в {@link SmsSendException}. */
public interface SmsSender {

    /**
     * @param text текст сообщения: код и название сервиса, персональных данных в нём нет
     * @throws SmsSendException провайдер не принял сообщение или не ответил вовремя
     */
    void send(PhoneNumber phone, String text);
}
