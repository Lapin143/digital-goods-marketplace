package dgm.platform.identity.otp.service;

/**
 * Отправка письма с кодом на новый адрес при смене e-mail (SEQ-04, шаг 13). Письмо уходит провайдеру сразу, минуя очередь {@code notification}:
 * код нельзя положить в базу (правило 17), а очередь хранит параметры письма до отправки. Ошибки провайдера превращаются в
 * {@link EmailCodeSendException}.
 */
public interface EmailCodeSender {

    /**
     * @param to   новый адрес в нормализованном виде
     * @param code одноразовый код из шести цифр
     * @throws EmailCodeSendException провайдер не принял письмо или не ответил вовремя
     */
    void send(String to, String code);
}
