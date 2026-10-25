package dgm.platform.auditadmin.testing;

import dgm.platform.auditadmin.domain.AuditFilter;
import dgm.platform.auditadmin.domain.AuditRecordView;
import dgm.platform.auditadmin.repository.AuditReadRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Журнал в памяти для проверки чтения: те же условия, порядок и курсор, что у запроса к базе; можно «сломать» чтение. */
public final class InMemoryAuditReader implements AuditReadRepository {

    private final List<AuditRecordView> records = new ArrayList<>();
    private final List<AuditFilter> requests = new ArrayList<>();
    private RuntimeException failure;

    public void add(AuditRecordView record) {
        records.add(record);
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    /** Условия, с которыми приходили за записями. */
    public List<AuditFilter> requests() {
        return List.copyOf(requests);
    }

    @Override
    public List<AuditRecordView> list(AuditFilter f) {
        requests.add(f);
        if (failure != null) {
            throw failure;
        }
        Predicate<AuditRecordView> keep = r -> f.actorId().map(r.actorId()::equals).orElse(true)
                && f.objectType().map(r.objectType()::equals).orElse(true)
                && f.objectId().map(r.objectId()::equals).orElse(true)
                && f.action().map(r.action()::equals).orElse(true)
                && f.occurredFrom().map(from -> !r.occurredAt().isBefore(from)).orElse(true)
                && f.occurredTo().map(to -> r.occurredAt().isBefore(to)).orElse(true)
                && f.after().map(a -> r.occurredAt().isBefore(a.occurredAt())
                        || r.occurredAt().equals(a.occurredAt()) && r.recordId().compareTo(a.id()) < 0).orElse(true);
        return records.stream().filter(keep)
                .sorted(Comparator.comparing(AuditRecordView::occurredAt).thenComparing(AuditRecordView::recordId).reversed())
                .limit(f.limit() + 1L).toList();
    }
}
