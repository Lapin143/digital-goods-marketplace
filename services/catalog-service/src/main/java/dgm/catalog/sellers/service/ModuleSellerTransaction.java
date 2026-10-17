package dgm.catalog.sellers.service;

import dgm.catalog.sellers.repository.JdbcSellerRepository;
import dgm.kit.boot.Modules;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link SellerTransaction} на пуле модуля {@code seller_onboarding} (каркас: {@link Modules}). */
@Component
public final class ModuleSellerTransaction implements SellerTransaction {

    private final Modules modules;

    public ModuleSellerTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(JdbcSellerRepository.MODULE).execute(status -> action.get());
    }
}
