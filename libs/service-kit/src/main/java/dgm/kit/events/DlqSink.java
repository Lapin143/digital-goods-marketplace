package dgm.kit.events;

import org.apache.kafka.clients.producer.ProducerRecord;

/** Приёмник темы недоставленного (conventions.md, 12.3). Отправка синхронная: смещение подтверждается только после подтверждения брокера. */
public interface DlqSink {

    void send(ProducerRecord<String, String> record) throws EventProcessingException;
}
