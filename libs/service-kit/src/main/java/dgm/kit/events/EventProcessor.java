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
import java.util.function.Function;
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
    private final Function<String, ProcessedEvents> processedByModule;
    private final Function<String, TransactionOperations> transactionsByModule;
    private final String defaultModule;
    private final DlqSink dlq;
    private final List<Duration> retryDelays;
    private final Clock clock;
    private final Sleeper sleeper;
    private final MeterRegistry metrics;

    /**
     * Сервис с одним модулем базы: все обработчики работают в одной транзакции и пишут {@code processed_event} через одно подключение.
     *
     * @param serviceName имя сервиса: значение {@code dlq-consumer} для записей, отклонённых до выбора обработчика
     */
    public EventProcessor(String serviceName, List<EventHandler> handlers, ProcessedEvents processed, TransactionOperations transactions,
                          DlqSink dlq, List<Duration> retryDelays, Clock clock, Sleeper sleeper, MeterRegistry metrics) {
        this(serviceName, handlers, module -> processed, module -> transactions, "", dlq, retryDelays, clock, sleeper, metrics);
    }

    /**
     * Сервис с несколькими модулями базы: обработчик объявляет свой модуль ({@link EventHandler#module()}), и его транзакция и отметка
     * обработанного идут через подключение этого модуля. Обработчик без объявленного модуля работает в {@code defaultModule}.
     *
     * @param processedByModule    хранилище обработанных событий модуля
     * @param transactionsByModule транзакции модуля
     */
    public EventProcessor(String serviceName, List<EventHandler> handlers, Function<String, ProcessedEvents> processedByModule,
                          Function<String, TransactionOperations> transactionsByModule, String defaultModule, DlqSink dlq,
                          List<Duration> retryDelays, Clock clock, Sleeper sleeper, MeterRegistry metrics) {
        this.serviceName = serviceName;
        this.handlers = List.copyOf(handlers);
        this.processedByModule = processedByModule;
        this.transactionsByModule = transactionsByModule;
        this.defaultModule = defaultModule;
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
        String module = handler.module() == null ? defaultModule : handler.module();
        TransactionOperations transactions = transactionsByModule.apply(module);
        ProcessedEvents processed = processedByModule.apply(module);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                HandleResult result = handler instanceof ExternalStepsHandler external
                        ? applyWithExternalSteps(external, envelope, processed, transactions)
                        : transactions.execute(status -> apply(handler, envelope, processed));
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
    private HandleResult apply(EventHandler handler, EventEnvelope envelope, ProcessedEvents processed) {
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

    /**
     * Обработчик с внешними шагами: отсев дубля и {@code prepare} идут без транзакции, отметка и действие в одной короткой транзакции после них.
     * Если между отсевом и транзакцией событие успел обработать другой поток, {@code markProcessed} вернёт {@code false} и действие не выполнится.
     */
    private HandleResult applyWithExternalSteps(ExternalStepsHandler handler, EventEnvelope envelope, ProcessedEvents processed,
                                                TransactionOperations transactions) {
        if (processed.isProcessed(handler.name(), envelope.id())) {
            return DUPLICATE;
        }
        ExternalStepsHandler.TransactionalStep step;
        try {
            step = handler.prepare(envelope);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RetryableEventException("Обработчик завершился ошибкой " + e.getClass().getSimpleName(), e);
        }
        return transactions.execute(status -> {
            if (!processed.markProcessed(handler.name(), envelope.id())) {
                return DUPLICATE;
            }
            try {
                HandleResult result = step == null ? HandleResult.done() : step.apply();
                return result == null ? HandleResult.done() : result;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RetryableEventException("Обработчик завершился ошибкой " + e.getClass().getSimpleName(), e);
            }
        });
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
