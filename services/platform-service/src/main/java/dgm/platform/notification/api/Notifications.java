package dgm.platform.notification.api;

/**
 * Постановка уведомления в очередь для других модулей платформы (подпакет {@code api}, правило модульности 1): модуль называет получателя
 * идентификатором, шаблон и параметры, а адрес, текст письма и отправку берёт на себя notification.
 */
public interface Notifications {

    /**
     * Ставит письмо в очередь отправки. Вызывается внутри транзакции модуля {@code notification} (обработчик события держит её сам).
     *
     * @return {@code false}: по этому событию и шаблону уведомление уже есть, второе письмо не создано (ADR-006)
     * @throws InvalidNotificationException шаблона нет или параметры ему не подходят: повтор не поможет
     */
    boolean enqueue(NotificationRequest request);
}
