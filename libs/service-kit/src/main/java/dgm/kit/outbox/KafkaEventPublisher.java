package dgm.kit.outbox;

import dgm.kit.kafka.KafkaClients;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;

/** Отправитель в Kafka: синхронно, {@code acks=all}, идемпотентный производитель (ADR-003, ADR-005). */
public final class KafkaEventPublisher implements EventPublisher {

    private final Producer<String, String> producer;
    private final Duration timeout;

    public KafkaEventPublisher(Producer<String, String> producer, Duration timeout) {
        this.producer = producer;
        this.timeout = timeout;
    }

    /**
     * Производитель с настройками проекта.
     *
     * @param bootstrap адрес брокера, например {@code kafka:9093}
     * @param security  настройки TLS клиента ({@code TlsMaterial.kafkaProperties()})
     * @param clientId  имя клиента, равно имени сервиса
     */
    public static KafkaEventPublisher create(String bootstrap, Map<String, Object> security, String clientId) {
        return new KafkaEventPublisher(KafkaClients.producer(bootstrap, security, clientId), Duration.ofSeconds(25));
    }

    @Override
    public void publish(OutgoingEvent event) throws PublishException {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.value());
        record.headers().add("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", event.type().getBytes(StandardCharsets.UTF_8));
        try {
            producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishException("Отправка события " + event.eventId() + " прервана", e, false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new PublishException("Брокер не принял событие " + event.eventId() + ": " + cause.getClass().getSimpleName(), cause,
                    cause instanceof RecordTooLargeException);
        } catch (TimeoutException e) {
            throw new PublishException("Нет подтверждения брокера за " + timeout.toSeconds() + " с для события " + event.eventId(), e, false);
        } catch (RuntimeException e) {
            throw new PublishException("Событие " + event.eventId() + " не отправлено: " + e.getClass().getSimpleName(), e, false);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
