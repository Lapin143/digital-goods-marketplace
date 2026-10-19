package dgm.order.orders.events;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.events.RetryableEventException;
import dgm.kit.time.Timestamps;
import dgm.order.orders.service.SagaOutcome;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Разбор данных входящих событий по схемам AsyncAPI и перевод итога оркестратора в ответ каркаса. Любое нарушение схемы это неисправимая
 * ошибка ({@link FatalEventException}): повтор не поможет, событие уходит в DLQ сразу.
 */
final class EventData {

    private static final Pattern ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private EventData() {
    }

    /** Идентификатор в каноническом виде (строчные буквы), как требует контракт. */
    static UUID uuid(EventEnvelope event, String field) {
        if (event.data().get(field) instanceof String value && ID.matcher(value).matches()) {
            return UUID.fromString(value);
        }
        throw new FatalEventException("В событии " + event.id() + " поле " + field + " не идентификатор");
    }

    static Instant instant(EventEnvelope event, String field) {
        if (event.data().get(field) instanceof String value) {
            return Timestamps.parse(value).orElseThrow(() -> new FatalEventException("В событии " + event.id() + " поле " + field + " не момент времени"));
        }
        throw new FatalEventException("В событии " + event.id() + " нет поля " + field);
    }

    /** Строка из перечисления контракта. */
    static String oneOf(EventEnvelope event, String field, Set<String> allowed) {
        if (event.data().get(field) instanceof String value && allowed.contains(value)) {
            return value;
        }
        throw new FatalEventException("В событии " + event.id() + " поле " + field + " вне перечисления контракта");
    }

    /** Применено и проигнорировано отвечают каркасу сразу, «позже» это временная ошибка: повторы 1, 5, 25 секунд, затем DLQ. */
    static HandleResult result(SagaOutcome outcome) {
        return switch (outcome.kind()) {
            case APPLIED -> HandleResult.done();
            case IGNORED -> HandleResult.ignored(outcome.reason());
            case RETRY -> throw new RetryableEventException("Событие нужно обработать позже: " + outcome.reason());
        };
    }
}
