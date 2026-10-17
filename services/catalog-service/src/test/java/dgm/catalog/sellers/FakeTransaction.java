package dgm.catalog.sellers;

import dgm.catalog.sellers.service.SellerTransaction;
import java.util.function.Supplier;

/** Транзакция без базы: выполняет действие сразу и считает вызовы. */
public final class FakeTransaction implements SellerTransaction {

    private int executions;

    @Override
    public <T> T execute(Supplier<T> action) {
        executions++;
        return action.get();
    }

    public int executions() {
        return executions;
    }
}
