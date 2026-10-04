package dgm.kit.outbox;

/** Отправитель событий в брокер. Отправка синхронная: метод возвращает управление после подтверждения брокера. */
public interface EventPublisher extends AutoCloseable {

    /** Отправляет событие и ждёт подтверждения (acks=all). */
    void publish(OutgoingEvent event) throws PublishException;

    @Override
    void close();
}
