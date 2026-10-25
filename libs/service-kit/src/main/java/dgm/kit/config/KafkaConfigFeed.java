package dgm.kit.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Чтение сжатой темы {@code platform.config} для слушателя: без группы потребителей ({@code assign}), смещения не подтверждаются,
 * чтение всегда с начала темы. Так новый экземпляр сервиса получает актуальные значения без запроса к платформе, а запись одной темы
 * не мешает потребителю событий (у того своя группа и свой поток).
 */
public final class KafkaConfigFeed implements ConfigFeed {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfigFeed.class);

    private final Consumer<String, String> consumer;
    private final String topic;
    private boolean assigned;

    public KafkaConfigFeed(Consumer<String, String> consumer, String topic) {
        this.consumer = consumer;
        this.topic = topic;
    }

    @Override
    public List<String> poll(Duration timeout) {
        if (!assigned) {
            assign();
        }
        List<String> values = new ArrayList<>();
        try {
            for (ConsumerRecord<String, String> record : consumer.poll(timeout)) {
                if (record.value() != null) {
                    values.add(record.value());
                }
            }
        } catch (WakeupException e) {
            LOG.debug("Ожидание записей параметров прервано");
        }
        return values;
    }

    private void assign() {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException("Тема " + topic + " недоступна или не создана");
        }
        List<TopicPartition> partitions = infos.stream().map(info -> new TopicPartition(topic, info.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
        assigned = true;
    }

    @Override
    public void wakeup() {
        consumer.wakeup();
    }

    @Override
    public void close() {
        try {
            consumer.close(Duration.ofSeconds(5));
        } catch (RuntimeException e) {
            LOG.warn("Потребитель параметров закрыт с ошибкой: {}", e.getMessage());
        }
    }
}
