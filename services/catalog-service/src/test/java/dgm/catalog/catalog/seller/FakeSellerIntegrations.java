package dgm.catalog.catalog.seller;

import dgm.catalog.sellers.api.SellerIntegrations;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Действующие подключения систем продавцов для модульных тестов: интерфейс модуля {@code sellers} без базы. Считает обращения и умеет
 * выполнять действие в момент обращения (тест проверяет, что обращение идёт не внутри транзакции каталога).
 */
public final class FakeSellerIntegrations implements SellerIntegrations {

    private final Set<UUID> active = new HashSet<>();
    private int checks;
    private Runnable onCheck = () -> { };

    public FakeSellerIntegrations connect(UUID sellerId) {
        active.add(sellerId);
        return this;
    }

    /** Подключение отключено или удалено: для платформы это одно и то же. */
    public FakeSellerIntegrations disconnect(UUID sellerId) {
        active.remove(sellerId);
        return this;
    }

    public FakeSellerIntegrations onCheck(Runnable action) {
        this.onCheck = action;
        return this;
    }

    /** Сколько раз спросили, действует ли подключение. */
    public int checks() {
        return checks;
    }

    @Override
    public boolean hasActive(UUID sellerId) {
        checks++;
        onCheck.run();
        return active.contains(sellerId);
    }
}
