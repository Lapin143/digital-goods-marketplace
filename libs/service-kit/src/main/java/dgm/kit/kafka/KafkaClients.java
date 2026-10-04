package dgm.kit.kafka;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Клиенты Kafka с настройками проекта (ADR-003, ADR-011, conventions.md, раздел 12). Настройки безопасности приходят извне
 * ({@code TlsMaterial.kafkaProperties()}), здесь только поведение: подтверждения, идемпотентность, ручная фиксация смещений.
 */
public final class KafkaClients {

    private KafkaClients() {
    }

    /** Производитель: {@code acks=all}, идемпотентный, ожидание ограничено по времени. */
    public static KafkaProducer<String, String> producer(String bootstrap, Map<String, Object> security, String clientId) {
        Map<String, Object> props = new LinkedHashMap<>(security);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20_000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
        return new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
    }

    /**
     * Потребитель: смещения подтверждает код, а не клиент (ADR-003); {@code max.poll.interval.ms} не меньше 5 минут, чтобы повторы
     * на 31 секунду не считались зависанием; пачка мала, чтобы худший случай (повторы по всей пачке) уложился в этот срок.
     *
     * @param groupId группа потребителей, равна имени сервиса (права Kafka выданы на группу с этим именем)
     */
    public static KafkaConsumer<String, String> consumer(String bootstrap, Map<String, Object> security, String groupId, String clientId) {
        Map<String, Object> props = new LinkedHashMap<>(security);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        return new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
    }
}
