package dgm.platform.auditadmin.repository;

import dgm.platform.auditadmin.domain.PlatformParameter;
import java.util.List;
import java.util.UUID;

/** Параметры платформы под ролью {@code app_audit_admin}: чтение, блокировка строк и запись значения. */
public interface ParameterRepository {

    /** Все параметры по ключу. */
    List<PlatformParameter> findAll();

    /**
     * Блокирует строки перечисленных ключей до конца транзакции и возвращает их. Блокировка берётся в порядке ключей по возрастанию,
     * каким бы ни был порядок в {@code keys}: две транзакции с общими строками не могут взять их навстречу друг другу.
     */
    List<PlatformParameter> lock(List<String> keys);

    /** Записывает значение; версию и время ставит база, возвращается строка после правки. */
    PlatformParameter updateValue(String key, long value, UUID updatedBy);

    /**
     * Пробует взять блокировку переиздания значений до конца транзакции: из нескольких экземпляров, стартующих вместе, переиздаёт один.
     */
    boolean tryReissueLock();
}
