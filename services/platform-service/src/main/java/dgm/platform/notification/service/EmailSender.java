package dgm.platform.notification.service;

/** Отправка письма провайдеру (компонент {@code email-sender}). Ошибки провайдера превращаются в {@link EmailSendException}. */
public interface EmailSender {

    /** @throws EmailSendException провайдер не принял письмо, не ответил вовремя или отверг его */
    void send(OutgoingEmail mail);
}
