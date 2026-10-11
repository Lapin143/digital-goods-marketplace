package dgm.kit.events;

import java.util.Set;

/**
 * Обработчик событий одной темы: компонент внутри сервиса со своим именем в {@code processed_event.consumer}
 * (conventions.md, 12.1). Метод {@link #handle} вызывается внутри транзакции базы сервиса, в той же транзакции каркас записывает
 * строку {@code processed_event}: если обработчик бросил исключение, откатывается всё, и повтор пройдёт заново.
 */
public interface EventHandler {

    /** Имя обработчика, уникальное в базе сервиса (например {@code saga-orchestrator}). */
    String name();

    /**
     * Модуль базы, под рабочей ролью которого обработчик пишет данные: транзакция, отметка {@code processed_event} и записи в Outbox идут
     * через соединение этого модуля. {@code null}: модуль потребителя по умолчанию ({@code dgm.consumer.module}). Нужен сервисам, где
     * модули разделены ролями (platform-service: уведомления пишет роль модуля notification, а не identity).
     */
    default String module() {
        return null;
    }

    /** Тема, из которой обработчик получает события. */
    String topic();

    /** Типы событий, которые обработчик принимает ({@code order.paid}). Остальные типы темы пропускаются. */
    Set<String> types();

    /** Старшая версия схемы {@code data}, которую обработчик понимает. События с большей версией пропускаются (метрика skipped). */
    int maxSchemaVersion();

    /**
     * Применяет событие внутри открытой транзакции. Записи в Outbox и аудит делаются здесь же.
     *
     * @throws FatalEventException       неисправимая ошибка: DLQ сразу
     * @throws RetryableEventException   временная ошибка: повторы 1, 5, 25 секунд, затем DLQ
     */
    HandleResult handle(EventEnvelope event) throws Exception;
}
