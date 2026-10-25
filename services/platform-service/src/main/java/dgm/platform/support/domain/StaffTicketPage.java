package dgm.platform.support.domain;

import java.util.List;
import java.util.Optional;

/** Страница очереди оператора и позиция следующей (пусто на последней странице). */
public record StaffTicketPage(List<StaffTicket> items, int limit, Optional<QueueFilter.After> next) {

    public StaffTicketPage {
        items = List.copyOf(items);
    }
}
