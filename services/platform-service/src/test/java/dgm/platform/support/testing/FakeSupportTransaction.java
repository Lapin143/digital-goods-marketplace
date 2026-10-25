package dgm.platform.support.testing;

import dgm.platform.support.service.SupportTransaction;
import java.util.function.Supplier;

/** Транзакция без базы: изменения обращений из упавшего действия откатываются, как в базе, а события в {@link RecordingOutbox} пишутся только при успехе действия. */
public final class FakeSupportTransaction implements SupportTransaction {

    private final InMemorySupport repository;

    public FakeSupportTransaction(InMemorySupport repository) {
        this.repository = repository;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        var snapshot = repository.snapshot();
        try {
            return action.get();
        } catch (RuntimeException | Error e) {
            repository.restore(snapshot);
            throw e;
        }
    }
}
