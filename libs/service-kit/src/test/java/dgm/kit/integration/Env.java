package dgm.kit.integration;

import dgm.kit.testing.Stand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Общее для интеграционных тестов: базы стенда, чтение тем суперпользователем, ожидание условия. */
final class Env {

    /** Метка запуска: идентификаторы агрегатов тестов начинаются с неё, чужие записи в темах стенда не мешают. */
    static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private Env() {
    }

    static DataSource orderDb() {
        return Stand.dataSource("app_orders", "order_db");
    }

    static DataSource inventoryDb() {
        return Stand.dataSource("app_inventory", "inventory_db");
    }

    static String subject(String name) {
        return "it-" + RUN + "-" + name;
    }

    /** Ждёт условия, проверяя его каждые 100 мс. Возвращает false, если срок вышел. */
    static boolean await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    /**
     * Читает тему с начала всеми партициями без группы потребителей, от имени kafka-init (суперпользователь стенда: права на чтение
     * тем недоставленного у сервисов нет). Возвращает записи, подходящие под условие, ждёт не дольше срока или пока не наберётся {@code min}.
     */
    static List<ConsumerRecord<String, String>> readTopic(String topic, Predicate<ConsumerRecord<String, String>> filter, int min, Duration timeout) {
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>(Stand.kafkaSecurity("kafka-init"));
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Stand.kafkaBootstrap());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "it-reader-" + RUN);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : consumer.partitionsFor(topic)) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && found.size() < min) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (filter.test(record)) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
