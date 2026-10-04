package dgm.kit.events;

import dgm.kit.log.Redaction;
import dgm.kit.trace.TraceContext;
import dgm.kit.trace.TraceContexts;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Обработка одной записи потребителем (компонент {@code event-consumer}, conventions.md, раздел 12, ADR-011).
 *
 * <p>Порядок: отбор по заголовку {@code ce_type} без разбора тела, проверка конверта (неверный уходит в DLQ сразу), версия схемы, затем
 * для каждого подходящего обработчика одна транзакция: строка {@code processed_event} и действие обработчика вместе. Дубль
 * (строка уже есть) завершается без действия. Временная ошибка повторяется с паузами 1, 5 и 25 секунд, потом запись уходит в
 * {@code <тема>.dlq}. Метод возвращает управление, когда запись можно подтвердить; исключение {@link EventProcessingException} значит,
 * что подтверждать нельзя.
 */
public final class EventProcessor {

    /** Пауза между попытками: после первой неудачи 1 с, после второй 5 с, после третьей 25 с, итого до 31 с (ADR-011). */
    public static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(25));

    /** Приостановка потока; в тестах вместо настоящего ожидания. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger LOG = LoggerFactory.getLogger(EventProcessor.class);
    private static final HandleResult DUPLICATE = new HandleResult(false, "duplicate");

    private final String serviceName;
    private final List<EventHandler> handlers;
    private final ProcessedEvents processed;
    private final TransactionOperations transactions;
    private final DlqSink dlq;
    private final List<Duration> retryDelays;
    private final Clock clock;
    private final Sleeper sleeper;
    private final MeterRegistry metrics;

    /**
     * @param serviceName имя сервиса: значение {@code dlq-consumer} для записей, отклонённых до выбора обработчика
     */
    public EventProcessor(String serviceName, List<EventHandler> handlers, ProcessedEvents processed, TransactionOperations transactions,
                          DlqSink dlq, List<Duration> retryDelays, Clock clock, Sleeper sleeper, MeterRegistry metrics) {
        this.serviceName = serviceName;
        this.handlers = List.copyOf(handlers);
        this.processed = processed;
        this.transactions = transactions;
        this.dlq = dlq;
        this.retryDelays = List.copyOf(retryDelays);
        this.clock = clock;
        this.sleeper = sleeper;
        this.metrics = metrics;
    }

    /** Темы, на которые нужно подписаться, чтобы получать события всех обработчиков. */
    public List<String> topics() {
        return handlers.stream().map(EventHandler::topic).distinct().sorted().toList();
    }

    public void process(ConsumerRecord<String, String> record) throws EventProcessingException {
        List<EventHandler> ofTopic = handlers.stream().filter(h -> h.topic().equals(record.topic())).toList();
        String headerType = header(record, "ce_type");
        if (ofTopic.isEmpty()) {
            skipped("unknown-topic");
            return;
        }
        if (headerType != null && ofTopic.stream().noneMatch(h -> h.types().contains(headerType))) {
            skipped("unknown-type");
            return;
        }

        EventEnvelope envelope;
        try {
            envelope = EventEnvelope.parse(record.value());
            if (record.key() != null && !record.key().equals(envelope.subject())) {
                throw new InvalidEventException("Ключ записи не равен subject конверта");
            }
        } catch (InvalidEventException e) {
            sendToDlq(record, serviceName, e, 1);
            return;
        }

        List<EventHandler> matching = ofTopic.stream().filter(h -> h.types().contains(envelope.type())).toList();
        if (matching.isEmpty()) {
            skipped("unknown-type");
            return;
        }
        TraceContexts.Scope scope = TraceContexts.open(TraceContext.incoming(envelope.traceparent(), envelope.correlationId()));
        try {
            for (EventHandler handler : matching) {
                if (envelope.schemaVersion() > handler.maxSchemaVersion()) {
                    skipped("unknown-version");
                    continue;
                }
                run(handler, record, envelope);
            }
        } finally {
            scope.close();
        }
    }

    private void run(EventHandler handler, ConsumerRecord<String, String> record, EventEnvelope envelope) throws EventProcessingException {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HandleResult result = transactions.execute(status -> apply(handler, envelope));
                recordResult(handler, envelope, result);
                return;
            } catch (FatalEventException e) {
                sendToDlq(record, handler.name(), e, attempt);
                return;
            } catch (RuntimeException e) {
                if (attempt > retryDelays.size()) {
                    sendToDlq(record, handler.name(), e, attempt);
                    return;
                }
                Duration pause = retryDelays.get(attempt - 1);
                metrics.counter("events_retries_total", "consumer", handler.name()).increment();
                LOG.warn("Обработчик {} не справился с событием {} (попытка {}), повтор через {} с: {}", handler.name(), envelope.id(),
                        attempt, pause.toSeconds(), e.getClass().getSimpleName());
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new EventProcessingException("Обработка события " + envelope.id() + " прервана", interrupted);
                }
            }
        }
    }

    /** Тело транзакции: отметка обработанного и действие обработчика. Любое исключение откатывает обе записи. */
    private HandleResult apply(EventHandler handler, EventEnvelope envelope) {
        if (!processed.markProcessed(handler.name(), envelope.id())) {
            return DUPLICATE;
        }
        try {
            HandleResult result = handler.handle(envelope);
            return result == null ? HandleResult.done() : result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableEventException("Обработчик завершился ошибкой " + e.getClass().getSimpleName(), e);
        }
    }

    private void recordResult(EventHandler handler, EventEnvelope envelope, HandleResult result) {
        if (result == DUPLICATE) {
            metrics.counter("events_processed_total", "consumer", handler.name(), "type", envelope.type(), "result", "duplicate").increment();
        } else if (result != null && result.applied()) {
            metrics.counter("events_processed_total", "consumer", handler.name(), "type", envelope.type(), "result", "applied").increment();
        } else {
            String reason = result == null ? "" : result.reason();
            metrics.counter("events_ignored_total", "reason", label(reason)).increment();
        }
    }

    private void skipped(String reason) {
        metrics.counter("events_skipped_total", "reason", reason).increment();
    }

    private void sendToDlq(ConsumerRecord<String, String> record, String consumer, Exception error, int attempts)
            throws EventProcessingException {
        ProducerRecord<String, String> out = new ProducerRecord<>(record.topic() + ".dlq", record.partition(), record.key(), record.value());
        for (Header h : record.headers()) {
            if (!h.key().startsWith("dlq-")) {
                out.headers().add(h);
            }
        }
        add(out, "dlq-original-topic", record.topic());
        add(out, "dlq-original-partition", Integer.toString(record.partition()));
        add(out, "dlq-original-offset", Long.toString(record.offset()));
        add(out, "dlq-consumer", consumer);
        add(out, "dlq-error-class", error.getClass().getName());
        add(out, "dlq-error-message", Redaction.mask(error.getMessage(), 300));
        add(out, "dlq-attempts", Integer.toString(attempts));
        add(out, "dlq-failed-at", DateTimeFormatter.ISO_INSTANT.format(clock.instant()));
        dlq.send(out);
        metrics.counter("events_dlq_total", "consumer", consumer).increment();
        LOG.error("Запись {}[{}]@{} отправлена в {}: {} ({})", record.topic(), record.partition(), record.offset(), out.topic(),
                error.getClass().getSimpleName(), Redaction.mask(error.getMessage(), 200));
    }

    private static void add(ProducerRecord<String, String> out, String key, String value) {
        out.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        Header h = record.headers().lastHeader(key);
        return h == null || h.value() == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** Значение метки метрики: строчные латинские буквы, цифры, дефис и подчёркивание, не длиннее 40 знаков. */
    static String label(String reason) {
        String s = reason == null || reason.isBlank() ? "unspecified" : reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
