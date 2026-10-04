package dgm.kit.events;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Цикл потребителя Kafka (компонент {@code event-consumer}): читает записи, передаёт каждую в {@link EventProcessor} и после
 * завершения обработки подтверждает смещение этой записи ({@code enable.auto.commit=false}, conventions.md, 12.1, шаг 7). Записи
 * одной партиции обрабатываются по порядку. Если запись довести не удалось (тема недоставленного недоступна), смещение не
 * подтверждается, поток возвращается к этой записи и пробует позже.
 */
public final class EventConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventConsumer.class);

    private final Consumer<String, String> consumer;
    private final EventProcessor processor;
    private final Duration pollTimeout;
    private final Duration failurePause;

    private volatile boolean running;
    private Thread thread;

    public EventConsumer(Consumer<String, String> consumer, EventProcessor processor, Duration pollTimeout, Duration failurePause) {
        this.consumer = consumer;
        this.processor = processor;
        this.pollTimeout = pollTimeout;
        this.failurePause = failurePause;
    }

    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "event-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }

    private void loop() {
        try {
            consumer.subscribe(processor.topics());
            while (running) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(pollTimeout);
                } catch (WakeupException e) {
                    continue;
                }
                handle(records);
            }
        } finally {
            try {
                consumer.close();
            } catch (RuntimeException e) {
                LOG.warn("Потребитель закрыт с ошибкой: {}", e.getMessage());
            }
        }
    }

    /** Обрабатывает пачку: каждую запись по порядку партиции, подтверждение после каждой. */
    void handle(ConsumerRecords<String, String> records) {
        Map<TopicPartition, Long> next = new HashMap<>();
        boolean failed = false;
        try {
            for (TopicPartition partition : records.partitions()) {
                List<ConsumerRecord<String, String>> list = records.records(partition);
                next.put(partition, list.get(0).offset());
                for (ConsumerRecord<String, String> record : list) {
                    try {
                        processor.process(record);
                    } catch (EventProcessingException e) {
                        LOG.error("Запись {}[{}]@{} не обработана до конца, вернусь к ней: {}", record.topic(), record.partition(),
                                record.offset(), e.getMessage());
                        failed = true;
                        break;
                    }
                    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                    next.put(partition, record.offset() + 1);
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Пачка записей обработана с ошибкой, смещения возвращены: {}", e.getMessage(), e);
            failed = true;
        }
        if (failed) {
            rewind(next);
            pause();
        }
    }

    /** Возвращает позицию чтения к первой неподтверждённой записи каждой партиции. */
    private void rewind(Map<TopicPartition, Long> next) {
        next.forEach((partition, offset) -> {
            try {
                consumer.seek(partition, offset);
            } catch (RuntimeException e) {
                // партиция отозвана при перебалансировке: её заново прочитает новый владелец с подтверждённого смещения
                LOG.debug("Позиция {} не возвращена: {}", partition, e.getMessage());
            }
        });
    }

    private void pause() {
        try {
            Thread.sleep(failurePause.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** Останавливает цикл; потребитель закрывается в своём потоке (клиент Kafka не потокобезопасен, кроме {@code wakeup}). */
    @Override
    public synchronized void close() {
        running = false;
        if (thread != null) {
            consumer.wakeup();
            try {
                thread.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }
}
