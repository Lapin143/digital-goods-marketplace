package dgm.platform.notification.service;

/**
 * Письмо для провайдера почты. Живёт только в памяти на время вызова.
 *
 * @param messageId идентификатор сообщения у провайдера: равен идентификатору уведомления, поэтому повторная отправка того же письма
 *                  провайдер распознаёт и не дублирует
 * @param to        адрес получателя
 * @param subject   тема
 * @param text      текст письма
 */
public record OutgoingEmail(String messageId, String to, String subject, String text) {

    /** Только идентификатор сообщения: адрес и текст в журнал не попадают. */
    @Override
    public String toString() {
        return "OutgoingEmail[" + messageId + "]";
    }
}
