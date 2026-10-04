package dgm.fixtures;

import org.apache.kafka.clients.producer.KafkaProducer;

/** Нарушение: прямая публикация в Kafka мимо Outbox. */
public final class DirectProducer {

    private final KafkaProducer<String, String> producer;

    public DirectProducer(KafkaProducer<String, String> producer) {
        this.producer = producer;
    }

    public void close() {
        producer.close();
    }
}
