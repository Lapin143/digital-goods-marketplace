package dgm.catalog.sellers.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Страница очереди: заявки по возрастанию момента подачи (старые выше) и ключ следующей страницы. Ключ это пара «момент подачи,
 * идентификатор»: порядок однозначен, и повторное чтение с ключом не пропускает и не повторяет заявки.
 *
 * @param at момент чтения по часам сервиса: от него считается признак просрочки каждой строки
 */
public record ReviewQueuePage(List<ReviewQueueItem> items, int limit, Optional<After> next, Instant at) {

    /** Заявки строго после этой пары в порядке очереди. */
    public record After(Instant submittedAt, UUID id) {
    }
}
