package dgm.delivery.delivery.service;

import dgm.delivery.delivery.client.SellerKeyStore;
import dgm.delivery.delivery.domain.KeyStoreResult;
import dgm.delivery.delivery.domain.SellerRequestState;
import dgm.delivery.delivery.repository.SellerRequestRepository;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Приём ответа продавца на запрос выдачи ({@code respondSellerDelivery}, OP-101, ADR-028, Р-190, Р-191). Порядок исключает потерю ключа при гонке с
 * тайм-аутом:
 *
 * <ol>
 *   <li>короткая запись берёт <b>аренду</b> на приём ответа ({@code lease_until = now + 30 секунд}) условным {@code UPDATE}: только пока срок не вышел и
 *       чужой аренды нет. Ответ, начатый до конца окна, имеет право закончиться, тайм-аут запрос с арендой не берёт; начатый после окна получает 409
 *       {@code request-expired};
 *   <li>вне транзакции (правило 9) ключи уходят в хранилище ключей ({@code storeSellerKeys}): шифрование, привязка к заказу, статус {@code issued};
 *   <li>вторая короткая запись условным {@code UPDATE} переводит запрос в «ответ получен» и снимает аренду: диспетчер берёт выдачу в отправку письма.
 * </ol>
 *
 * <p>Хранилище недоступно: ключи не потеряны, аренда снимается, ответ 503, продавец повторяет вызов. Хранилище отказало по существу (число ключей, дубликат):
 * аренда снимается, продавец исправляет ответ в пределах окна. Идемпотентность по {@code requestId}: повтор тех же ключей даёт тот же итог (хранилище сверяет
 * значения по HMAC), другие ключи 409 {@code request-already-answered}. Ответ с {@code error} закрывает запрос отказом продавца, заказ отменяется (T10).
 *
 * <p>Запрос чужого продавца неотличим от несуществующего: 404 (U24). Значений ключей в журнале, метриках и ошибках нет (INV-20). Известное ограничение:
 * если хранилище отвечает дольше аренды (30 секунд) и в это время вышел тайм-аут, ключи окажутся сохранены у заказа, который отменяется; хранилище
 * отвечает за 2 секунды с одним повтором, поэтому аренда заведомо длиннее вызова.
 */
public final class SellerAnswerService {

    private static final Logger LOG = LoggerFactory.getLogger(SellerAnswerService.class);

    /** Коды отказа продавца: перечисление открытое, остальные значения записываются как {@code other}. */
    static final Set<String> KNOWN_ERRORS = Set.of("out_of_stock", "cannot_issue", "other");

    /** Ответ продавца: либо ключи, либо отказ. */
    public sealed interface Answer {

        /** Значения ключей, по одному на единицу заказа. */
        record Keys(List<String> values) implements Answer {
        }

        /** Продавец выдать не может. */
        record Refusal(String code, String message) implements Answer {
        }
    }

    /** Итог приёма: {@code stored} пуст у отказа продавца. */
    public record Result(UUID requestId, String status, Integer stored) {
    }

    private final SellerRequestRepository requests;
    private final SellerKeyStore keyStore;
    private final SellerRequestOutcomes outcomes;
    private final SellerSettings settings;
    private final Clock clock;
    private final MeterRegistry meters;

    public SellerAnswerService(SellerRequestRepository requests, SellerKeyStore keyStore, SellerRequestOutcomes outcomes, SellerSettings settings, Clock clock,
                               MeterRegistry meters) {
        this.requests = requests;
        this.keyStore = keyStore;
        this.outcomes = outcomes;
        this.settings = settings;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @param sellerId  продавец ключа API: должен совпасть с продавцом запроса
     * @param requestId запрос выдачи из тела запроса платформы
     * @throws ProblemException 404 чужой или неизвестный запрос; 409 {@code request-expired}, {@code request-already-answered}, {@code request-in-progress};
     *                          422 хранилище отклонило ключи; 503 хранилище недоступно
     */
    public Result answer(UUID sellerId, UUID requestId, Answer answer) {
        SellerRequestState state = requests.find(requestId).filter(s -> s.sellerId().equals(sellerId)).orElseThrow(SellerAnswerService::notFound);
        Result result = switch (answer) {
            case Answer.Keys keys -> keys(state, keys.values());
            case Answer.Refusal refusal -> refusal(state, refusal);
        };
        meters.counter("delivery_seller_answers_total", "outcome", result.status()).increment();
        return result;
    }

    private Result keys(SellerRequestState state, List<String> values) {
        if ("answered".equals(state.status())) {
            // повтор: хранилище отвечает, те же это ключи или другие
            return storeAndReport(state, values, false);
        }
        Instant now = clock.instant();
        Instant leaseUntil = acquire(state, now);
        try {
            return storeAndReport(state, values, true);
        } finally {
            if (leaseUntil != null) {
                requests.release(state.requestId());
            }
        }
    }

    /**
     * Берёт аренду на приём ответа или объясняет, почему нельзя.
     *
     * @return до какого момента действует аренда
     */
    private Instant acquire(SellerRequestState state, Instant now) {
        refuseIfClosed(state, now);
        Instant until = now.plus(settings.answerLease());
        if (requests.lease(state.requestId(), now, until)) {
            return until;
        }
        // условный UPDATE не прошёл: запрос ушёл дальше, срок вышел или аренду держит другой ответ
        SellerRequestState fresh = requests.find(state.requestId()).orElseThrow(SellerAnswerService::notFound);
        refuseIfClosed(fresh, now);
        throw new ProblemException(ProblemType.REQUEST_IN_PROGRESS, "Предыдущий ответ по этому запросу ещё обрабатывается, повторите вызов через несколько секунд.",
                Map.of(), Map.of("Retry-After", "5"));
    }

    private static void refuseIfClosed(SellerRequestState state, Instant now) {
        switch (state.status()) {
            case "answered" -> throw alreadyAnswered();
            case "failed" -> throw SellerRequestOutcomes.CODE_FAILED_BY_SELLER.contains(String.valueOf(state.errorCode())) ? alreadyAnswered() : expired();
            case "timeout" -> throw expired();
            default -> {
                if (!state.deadlineAt().isAfter(now)) {
                    throw expired();
                }
            }
        }
    }

    private Result storeAndReport(SellerRequestState state, List<String> values, boolean first) {
        KeyStoreResult stored = keyStore.store(state.orderId(), state.requestId(), values);
        return switch (stored) {
            case KeyStoreResult.Stored done -> {
                if (first) {
                    outcomes.answered(state.requestId());
                }
                yield new Result(state.requestId(), "answered", done.count());
            }
            case KeyStoreResult.Refused refused -> throw refused(state, refused, first);
            case KeyStoreResult.Unavailable unavailable -> {
                LOG.warn("Ключи по запросу {} не сохранены: {}, продавец повторит вызов", state.requestId(), unavailable.code());
                throw ProblemException.dependencyUnavailable("Хранилище ключей временно недоступно, ключи не потеряны: повторите вызов.", 5);
            }
        };
    }

    private ProblemException refused(SellerRequestState state, KeyStoreResult.Refused refused, boolean first) {
        return switch (refused.status()) {
            case 422 -> new ProblemException(ProblemType.VALIDATION_FAILED, "Ключи не приняты, подробности в поле errors.", Map.of("errors", refused.errors()),
                    Map.of());
            case 409 -> first ? new ProblemException(ProblemType.STATE_CONFLICT, "Оплата заказа не подтверждена или заказ уже отменён: ключи принять нельзя.",
                    Map.of(), Map.of()) : alreadyAnswered();
            default -> {
                // нарушение согласованности: запрос продавцу есть, а хранилище заказа не знает; ключей оно не приняло
                LOG.error("Хранилище ключей отклонило запрос {} кодом {}: нарушение согласованности", state.requestId(), refused.status());
                meters.counter("delivery_anomalies_total", "reason", "seller_keys_refused").increment();
                yield ProblemException.dependencyUnavailable("Хранилище ключей временно не принимает ключи: повторите вызов.", 30);
            }
        };
    }

    private Result refusal(SellerRequestState state, Answer.Refusal refusal) {
        if ("failed".equals(state.status()) && SellerRequestOutcomes.CODE_FAILED_BY_SELLER.contains(String.valueOf(state.errorCode()))) {
            // повтор того же отказа: тот же итог
            return new Result(state.requestId(), "failed", null);
        }
        acquire(state, clock.instant());
        String code = KNOWN_ERRORS.contains(refusal.code()) ? refusal.code() : "other";
        boolean applied = false;
        try {
            applied = outcomes.failedByAnswer(state, code, refusal.message());
        } finally {
            if (!applied) {
                requests.release(state.requestId());
            }
        }
        if (!applied) {
            throw new ProblemException(ProblemType.REQUEST_IN_PROGRESS, "Запрос уже изменяется другим ответом, повторите вызов через несколько секунд.",
                    Map.of(), Map.of("Retry-After", "5"));
        }
        return new Result(state.requestId(), "failed", null);
    }

    private static ProblemException notFound() {
        return new ProblemException(ProblemType.NOT_FOUND, "Запрос выдачи не найден.");
    }

    private static ProblemException expired() {
        return new ProblemException(ProblemType.REQUEST_EXPIRED, "Срок ответа на запрос вышел: заказ отменяется, деньги возвращаются.");
    }

    private static ProblemException alreadyAnswered() {
        return new ProblemException(ProblemType.REQUEST_ALREADY_ANSWERED, "Запрос уже получил ответ.");
    }
}
