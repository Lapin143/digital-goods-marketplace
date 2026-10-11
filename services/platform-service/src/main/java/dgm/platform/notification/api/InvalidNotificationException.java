package dgm.platform.notification.api;

/** Уведомление поставить нельзя: неизвестный шаблон или параметры не подходят шаблону. Сообщение называет поле, но не его значение. */
public final class InvalidNotificationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidNotificationException(String message) {
        super(message);
    }
}
