package dgm.kit.idempotency;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Охранник идемпотентности запросов (ADR-006, уровень 1). Выполняет действие не больше одного раза на пару «область, ключ» и отвечает
 * повторам тем же ответом.
 *
 * <p>Порядок для нового ключа: ключ занимается короткой отдельной транзакцией (его сразу видят параллельные повторы), затем в одной
 * транзакции выполняются действие и сохранение ответа. Откат действия оставляет базу без следов, ключ освобождается, и повтор
 * выполняется заново. Запоминается только успешный (2xx) ответ: ошибка клиента или сбой не должны навсегда закрепляться за ключом.
 *
 * <p>Повтор, пришедший пока первый запрос ещё выполняется, ждёт его завершения до {@link Settings#waitForOther()} и получает
 * сохранённый ответ; не дождавшись, получает 409 {@code request-in-progress} с {@code Retry-After: 1}. Ключ, занятый процессом,
 * который упал, подхватывается после {@link Settings#abandonedAfter()}.
 */
public final class IdempotencyGuard {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyGuard.class);

    /** Срок хранения ключей: 24 часа (ADR-006). */
    public static final Duration RETENTION = Duration.ofHours(24);

    /**
     * @param waitForOther   сколько повтор ждёт завершения первого запроса, пока тот выполняется
     * @param pollInterval   как часто повтор проверяет, завершился ли первый запрос
     * @param abandonedAfter через какое время запрос {@code in_progress} считается брошенным и его ключ можно подхватить
     */
    public record Settings(Duration waitForOther, Duration pollInterval, Duration abandonedAfter) {

        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(2), Duration.ofMillis(50), Duration.ofSeconds(60));
        }
    }

    /** Ожидание между проверками; в тестах заменяется. */
    @FunctionalInterface
    public interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    /**
     * @param response ответ, который нужно отдать клиенту
     * @param replayed {@code true}, если ответ взят из хранилища, а действие не выполнялось
     */
    public record Outcome(StoredResponse response, boolean replayed) {
    }

    private final IdempotencyStore store;
    private final TransactionOperations transactions;
    private final Settings settings;
    private final Clock clock;
    private final Pause pause;

    public IdempotencyGuard(IdempotencyStore store, TransactionOperations transactions, Settings settings, Clock clock, Pause pause) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    public IdempotencyGuard(IdempotencyStore store, TransactionOperations transactions, Settings settings, Clock clock) {
        this(store, transactions, settings, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    /**
     * Выполняет действие один раз на ключ.
     *
     * @param action действие: выполняется внутри транзакции, вместе с которой сохраняется ответ. Успешный (2xx) результат запоминается;
     *               неуспешный откатывает транзакцию и ключ освобождается. Исключение откатывает транзакцию и освобождает ключ
     * @throws ProblemException {@code idempotency-key-reuse} (422), если ключ уже использован для другого запроса;
     *                          {@code request-in-progress} (409), если первый запрос не завершился за время ожидания
     */
    public Outcome execute(IdempotencyRequest request, Supplier<StoredResponse> action) {
        return run(request, action, true);
    }

    /**
     * Выполняет действие один раз на ключ, но без общей транзакции (Р-150). Для операций, которые по ходу дела вызывают соседей или
     * внешние системы: вызов внутри открытой транзакции базы держит соединение на всё время ожидания и нарушает правило 9, а откат
     * общей транзакции стёр бы то, что операция должна оставить при сбое (платёж «создан» без внешнего идентификатора после 503).
     *
     * <p>Каждый шаг действия фиксируется сам. Ответ сохраняется отдельной короткой транзакцией после действия, поэтому между
     * действием и сохранением возможен сбой процесса: ключ останется {@code in_progress}, его подхватит повтор через
     * {@link Settings#abandonedAfter()} и выполнит действие заново. Действие обязано выдерживать повтор само: уникальные ограничения
     * предметной области и ключи идемпотентности вызовов соседей (ADR-006, уровни 1 и 3). Остальные правила как у {@link #execute}.
     */
    public Outcome executeOutsideTransaction(IdempotencyRequest request, Supplier<StoredResponse> action) {
        return run(request, action, false);
    }

    private Outcome run(IdempotencyRequest request, Supplier<StoredResponse> action, boolean inTransaction) {
        Instant deadline = clock.instant().plus(settings.waitForOther());
        while (true) {
            if (store.claim(request)) {
                return runOwned(request, action, inTransaction);
            }
            Optional<IdempotencyStore.Entry> found = store.find(request.scope(), request.key());
            if (found.isPresent()) {
                IdempotencyStore.Entry entry = found.get();
                if (!entry.endpoint().equals(request.endpoint()) || !Arrays.equals(entry.requestHash(), request.requestHash())) {
                    throw new ProblemException(ProblemType.IDEMPOTENCY_KEY_REUSE, "Этот ключ идемпотентности уже использован для другого запроса.");
                }
                if (entry.completed()) {
                    return new Outcome(entry.response(), true);
                }
                if (store.takeOver(request.scope(), request.key(), settings.abandonedAfter().toSeconds())) {
                    return runOwned(request, action, inTransaction);
                }
            }
            // Записи нет (её освободили между проверками) или первый запрос ещё выполняется: ждём и пробуем снова до срока
            if (!clock.instant().isBefore(deadline)) {
                throw new ProblemException(ProblemType.REQUEST_IN_PROGRESS, "Запрос с этим ключом ещё выполняется, повторите позже.",
                        Map.of(), Map.of("Retry-After", "1"));
            }
            try {
                pause.pause(settings.pollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProblemException(ProblemType.REQUEST_IN_PROGRESS, "Ожидание прервано, повторите запрос.", Map.of(),
                        Map.of("Retry-After", "1"));
            }
        }
    }

    private StoredResponse runInTransaction(IdempotencyRequest request, Supplier<StoredResponse> action) {
        return transactions.execute(status -> {
            StoredResponse result = action.get();
            if (result == null) {
                throw new IllegalStateException("Действие под охранником идемпотентности вернуло пустой результат");
            }
            if (result.successful()) {
                store.complete(request, result);
            } else {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private StoredResponse runOutsideTransaction(IdempotencyRequest request, Supplier<StoredResponse> action) {
        StoredResponse result = action.get();
        if (result == null) {
            throw new IllegalStateException("Действие под охранником идемпотентности вернуло пустой результат");
        }
        if (result.successful()) {
            transactions.executeWithoutResult(status -> store.complete(request, result));
        }
        return result;
    }

    private Outcome runOwned(IdempotencyRequest request, Supplier<StoredResponse> action, boolean inTransaction) {
        boolean stored = false;
        try {
            StoredResponse response = inTransaction ? runInTransaction(request, action) : runOutsideTransaction(request, action);
            stored = response != null && response.successful();
            return new Outcome(response, false);
        } finally {
            if (!stored) {
                try {
                    store.release(request);
                } catch (RuntimeException e) {
                    // Ключ останется занятым до подхвата или очистки; исходная ошибка важнее
                    LOG.warn("Не удалось освободить ключ идемпотентности: {}", e.getMessage());
                }
            }
        }
    }
}
