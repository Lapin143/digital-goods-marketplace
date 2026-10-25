package dgm.platform.support.service;

import dgm.kit.boot.Modules;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** {@link SupportTransaction} на пуле модуля {@code support} (каркас: {@link Modules}). */
@Component
public final class ModuleSupportTransaction implements SupportTransaction {

    private final Modules modules;

    public ModuleSupportTransaction(Modules modules) {
        this.modules = modules;
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        return modules.transactions(ModuleSupportOutbox.MODULE).execute(status -> action.get());
    }
}
