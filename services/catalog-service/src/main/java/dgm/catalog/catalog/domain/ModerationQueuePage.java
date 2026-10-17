package dgm.catalog.catalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Страница очереди товаров: по возрастанию момента отправки (старые выше) и ключ следующей страницы. Ключ это пара «момент отправки,
 * идентификатор»: порядок однозначен, и повторное чтение с ключом не пропускает и не повторяет товары.
 *
 * @param at момент чтения по часам сервиса: от него считается признак просрочки каждой строки
 */
public record ModerationQueuePage(List<ModerationQueueItem> items, int limit, Optional<After> next, Instant at) {

    /** Товары строго после этой пары в порядке очереди. */
    public record After(Instant submittedAt, UUID id) {
    }
}
