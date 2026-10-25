package dgm.platform.auditadmin.repository;

import dgm.platform.auditadmin.domain.AuditFilter;
import dgm.platform.auditadmin.domain.AuditRecordView;
import java.util.List;

/** Чтение журнала аудита под ролью {@code app_audit_admin}: только {@code SELECT} (INV-42). */
public interface AuditReadRepository {

    /** Записи по условиям, новые выше; на одну больше размера страницы, чтобы понять, есть ли следующая. */
    List<AuditRecordView> list(AuditFilter filter);
}
