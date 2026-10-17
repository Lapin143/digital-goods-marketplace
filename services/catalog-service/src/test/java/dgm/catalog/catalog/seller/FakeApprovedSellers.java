package dgm.catalog.catalog.seller;

import dgm.catalog.sellers.api.ApprovedSellers;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Список одобренных продавцов для модульных тестов: интерфейс модуля {@code sellers} без базы. */
public final class FakeApprovedSellers implements ApprovedSellers {

    private final Set<UUID> approved = new HashSet<>();

    public FakeApprovedSellers approve(UUID sellerId) {
        approved.add(sellerId);
        return this;
    }

    public FakeApprovedSellers revoke(UUID sellerId) {
        approved.remove(sellerId);
        return this;
    }

    @Override
    public boolean isApproved(UUID sellerId) {
        return approved.contains(sellerId);
    }
}
