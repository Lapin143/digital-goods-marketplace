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

    /**
     * Провайдер принял сообщение. Если о доставке провайдер успел сообщить раньше, чем записан исход отправки (вебхук обогнал запись), статус
     * сразу становится {@code delivered}.
     *
     * @return {@code false}: уведомление уже не в очереди
     */
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

    /**
     * Записывает статус письма от провайдера в {@code provider_status_event}; пара «уведомление, статус» уникальна, повтор ничего не добавляет.
     *
     * @return {@code false}: такой статус этого уведомления уже записан
     */
    boolean recordProviderStatus(UUID id, String providerStatus, Instant occurredAt, Instant at);

    /**
     * Письмо доставлено: {@code sent} в {@code delivered} одним условным обновлением.
     *
     * @return {@code false}: уведомления нет или оно не в статусе {@code sent} (ещё в очереди, уже доставлено или недоставлено)
     */
    boolean markDelivered(UUID id, Instant at);

    /**
     * Провайдер сообщил о недоставке: {@code queued} или {@code sent} в {@code failed} с признаком очереди недоставленных одним условным
     * обновлением. Условие по статусу гарантирует «один раз»: второй отказ того же письма, отказ после доставки и отказ после собственного исхода
     * платформы ({@link #markFailed}) строку не находят. Число попыток не меньше одной: провайдер сообщает о письме, которое принял.
     *
     * @return уведомление после перехода; пусто, если перехода не было
     */
    Optional<Notification> markFailedByProvider(UUID id, String errorCode, Instant at);
}
