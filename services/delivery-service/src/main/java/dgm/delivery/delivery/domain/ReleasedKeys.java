package dgm.delivery.delivery.domain;

import dgm.kit.secret.SecretValue;
import java.util.List;
import java.util.Objects;

/**
 * Значения ключей одного заказа, полученные у {@code inventory-service} на время одной попытки (ADR-009, INV-20). Живут в памяти: письмо
 * собрано и отправлено, {@link #close()} стирает значения. В {@link #toString()} только число ключей, значения не выводятся ни при каких условиях.
 */
public final class ReleasedKeys implements AutoCloseable {

    private final List<SecretValue> values;

    public ReleasedKeys(List<SecretValue> values) {
        this.values = List.copyOf(Objects.requireNonNull(values, "values"));
    }

    /** Сколько значений получено. */
    public int size() {
        return values.size();
    }

    /** Значения в порядке выдачи. Читать их разрешено только сборщику письма (правило архитектуры). */
    public List<SecretValue> values() {
        return values;
    }

    /** Стирает значения в памяти. */
    @Override
    public void close() {
        for (SecretValue value : values) {
            value.destroy();
        }
    }

    @Override
    public String toString() {
        return "ReleasedKeys[" + values.size() + "]";
    }
}
