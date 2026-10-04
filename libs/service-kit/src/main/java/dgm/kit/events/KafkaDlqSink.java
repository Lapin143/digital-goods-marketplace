package dgm.kit.events;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Запись в тему недоставленного через производитель Kafka ({@code acks=all}), с ожиданием подтверждения. */
public final class KafkaDlqSink implements DlqSink, AutoCloseable {

    private final Producer<String, String> producer;
    private final Duration timeout;

    public KafkaDlqSink(Producer<String, String> producer, Duration timeout) {
        this.producer = producer;
        this.timeout = timeout;
    }

    @Override
    public void send(ProducerRecord<String, String> record) throws EventProcessingException {
        try {
            producer.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventProcessingException("Запись в " + record.topic() + " прервана", e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new EventProcessingException("Запись в " + record.topic() + " не подтверждена брокером", e);
        }
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
