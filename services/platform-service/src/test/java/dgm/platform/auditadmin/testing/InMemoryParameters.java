package dgm.platform.auditadmin.testing;

import dgm.platform.auditadmin.domain.PlatformParameter;
import dgm.platform.auditadmin.repository.ParameterRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Параметры в памяти: версия растёт при каждой записи, как у триггера таблицы; порядок блокировок записывается для проверки. */
public final class InMemoryParameters implements ParameterRepository {

    private final Map<String, PlatformParameter> rows = new TreeMap<>();
    private final List<List<String>> locks = new ArrayList<>();
    private final Clock clock;
    private RuntimeException updateFailure;
    private boolean reissueLockFree = true;

    public InMemoryParameters(Clock clock) {
        this.clock = clock;
    }

    /** Параметр в версии 1: единицы и границы как в начальном засеве, там где важны тестам. */
    public InMemoryParameters add(String key, String unit, long value, long min, long max) {
        rows.put(key, new PlatformParameter(key, "Описание " + key, unit, value, value, min, max, 1, clock.instant(), null));
        return this;
    }

    /** Пара INV-07 и два соседа в границах начального засева. */
    public static InMemoryParameters seeded(Clock clock) {
        return new InMemoryParameters(clock)
                .add("payment-session.ttl-seconds", "seconds", 720, 60, 3599)
                .add("reservation.ttl-seconds", "seconds", 900, 300, 3600)
                .add("orders.max-unpaid", "count", 3, 1, 20);
    }

    public PlatformParameter stored(String key) {
        return rows.get(key);
    }

    /** Ключи каждого запроса на блокировку в порядке обращений. */
    public List<List<String>> locks() {
        return List.copyOf(locks);
    }

    public void failUpdateWith(RuntimeException failure) {
        this.updateFailure = failure;
    }

    public void reissueLockTakenByAnotherInstance() {
        this.reissueLockFree = false;
    }

    @Override
    public List<PlatformParameter> findAll() {
        return List.copyOf(rows.values());
    }

    @Override
    public List<PlatformParameter> lock(List<String> keys) {
        locks.add(List.copyOf(keys));
        List<PlatformParameter> found = new ArrayList<>();
        for (String key : new java.util.TreeSet<>(keys)) {
            if (rows.containsKey(key)) {
                found.add(rows.get(key));
            }
        }
        return found;
    }

    @Override
    public PlatformParameter updateValue(String key, long value, UUID updatedBy) {
        if (updateFailure != null) {
            throw updateFailure;
        }
        PlatformParameter old = rows.get(key);
        PlatformParameter changed = new PlatformParameter(key, old.description(), old.unit(), value, old.defaultValue(), old.minValue(), old.maxValue(),
                old.version() + 1, clock.instant(), updatedBy);
        rows.put(key, changed);
        return changed;
    }

    @Override
    public boolean tryReissueLock() {
        return reissueLockFree;
    }
}
