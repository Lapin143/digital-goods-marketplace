package dgm.platform.auditadmin.domain;

import java.util.List;
import java.util.Optional;

/** Страница записей журнала и позиция следующей (пусто на последней странице). */
public record AuditPage(List<AuditRecordView> items, int limit, Optional<AuditFilter.After> next) {

    public AuditPage {
        items = List.copyOf(items);
    }
}
