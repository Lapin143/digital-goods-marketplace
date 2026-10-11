package dgm.platform.notification.testing;

import dgm.platform.identity.api.Contact;
import dgm.platform.identity.api.UserContacts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Контакты получателей теста: активные записи; удалённая из набора запись для уведомлений «деактивирована». */
public final class FakeContacts implements UserContacts {

    private final Map<UUID, Contact> active = new LinkedHashMap<>();

    public void put(UUID userId, String email, String name) {
        active.put(userId, new Contact(userId, email, name));
    }

    public void deactivate(UUID userId) {
        active.remove(userId);
    }

    @Override
    public Optional<Contact> findActive(UUID userId) {
        return Optional.ofNullable(active.get(userId));
    }
}
