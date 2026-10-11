package dgm.platform.notification.repository;

import dgm.platform.notification.domain.NewNotification;
import dgm.platform.notification.domain.Notification;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Очередь уведомлений (схема {@code notification}, роль {@code app_notification}). Методы завершения вызываются после отправки, вне
 * транзакции с вызовом провайдера: соединение с базой на время сетевого вызова не удерживается.
 */
public interface NotificationRepository {

    /**
     * Ставит уведомление в очередь с первой попыткой в момент создания.
     *
     * @return {@code false}: по этому событию и шаблону уведомление уже есть, ничего не добавлено
     */
    boolean insert(NewNotification notification);

    /**
     * Берёт в работу одно уведомление, срок которого настал, и откладывает его срок на {@code leaseUntil}: пока идёт отправка, другие
     * экземпляры его не возьмут, а если экземпляр упадёт, уведомление вернётся в очередь по истечении аренды.
     */
    Optional<Notification> claim(Instant now, Instant leaseUntil);

    Optional<Notification> find(UUID id);

    /** Провайдер принял сообщение. @return {@code false}: уведомление уже не в очереди */
    boolean markSent(UUID id, int attempts, Instant at);

    /** Попытка не удалась, следующая назначена. @return {@code false}: уведомление уже не в очереди */
    boolean reschedule(UUID id, int attempts, Instant nextAttemptAt, String errorCode, Instant at);

    /**
     * Уведомление не будет отправлено.
     *
     * @param deadQueue признак «в очереди недоставленных»: письмо не доставлено после попыток, об этом публикуется {@code notification.failed}
     * @return {@code false}: уведомление уже не в очереди
     */
    boolean markFailed(UUID id, int attempts, String errorCode, boolean deadQueue, Instant at);
}
