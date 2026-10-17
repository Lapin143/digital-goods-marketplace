package dgm.platform.identity.sellerrole;

import dgm.kit.events.EventEnvelope;
import dgm.kit.events.EventHandler;
import dgm.kit.events.HandleResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Роль «Продавец» по решению модератора (компонент {@code seller-role-handler}, INV-36, INV-37; US-2.2): потребитель
 * {@code seller.approved} темы {@code catalog.events}. Единственный путь к роли: вручную её не назначает никто, а каталог не знает про
 * Keycloak. Обработчик работает внутри транзакции потребителя модуля {@code identity}; что в ней происходит и что остаётся на
 * после фиксации, описано в {@link SellerRoleService}.
 */
@Component
public final class SellerRoleHandler implements EventHandler {

    /** Имя потребителя в {@code processed_event}: так обработчик назван в контракте событий (x-consumer.handler). */
    static final String NAME = "seller-role-handler";

    private final SellerRoleService service;

    public SellerRoleHandler(SellerRoleService service) {
        this.service = service;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Роль пишется под ролью базы {@code app_identity}, а не модуля по умолчанию (там уведомления). */
    @Override
    public String module() {
        return "identity";
    }

    @Override
    public String topic() {
        return "catalog.events";
    }

    @Override
    public Set<String> types() {
        return Set.of("seller.approved");
    }

    @Override
    public int maxSchemaVersion() {
        return 1;
    }

    @Override
    public HandleResult handle(EventEnvelope event) {
        return service.assign(SellerApproval.parse(event.data()));
    }
}
