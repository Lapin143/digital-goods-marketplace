package dgm.platform.notification.testing;

import dgm.platform.identity.api.AdminRecipients;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Список активных администраторов, который тест задаёт сам. */
public final class FakeAdmins implements AdminRecipients {

    private final List<UUID> ids = new ArrayList<>();

    public FakeAdmins with(UUID... admins) {
        ids.addAll(List.of(admins));
        return this;
    }

    @Override
    public List<UUID> activeAdminIds() {
        return List.copyOf(ids);
    }
}
