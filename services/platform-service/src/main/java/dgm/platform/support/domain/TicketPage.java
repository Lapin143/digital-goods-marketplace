package dgm.platform.support.domain;

import java.util.List;
import java.util.Optional;

/** Страница обращений и позиция следующей (пусто на последней странице). */
public record TicketPage(List<SupportTicket> items, int limit, Optional<TicketFilter.After> next) {

    public TicketPage {
        items = List.copyOf(items);
    }
}
