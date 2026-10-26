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
    private final Map<UUID, Contact> previous = new LinkedHashMap<>();
    private final Map<UUID, Contact> deactivated = new LinkedHashMap<>();

    public void put(UUID userId, String email, String name) {
        active.put(userId, new Contact(userId, email, name));
    }

    /** Прежний адрес пользователя после смены e-mail: пока он хранится, письмо старому ящику получатель находит. */
    public void putPrevious(UUID userId, String email, String name) {
        previous.put(userId, new Contact(userId, email, name));
    }

    /** Срок хранения прежнего адреса вышел: адрес очищен. */
    public void expirePrevious(UUID userId) {
        previous.remove(userId);
    }

    /** Запись деактивирована: активным письма больше не пишут, а письмо о самой деактивации находит адрес. */
    public void deactivate(UUID userId) {
        Contact contact = active.remove(userId);
        if (contact != null) {
            deactivated.put(userId, contact);
        }
    }

    /** Запись обезличена: адреса нет нигде. */
    public void anonymize(UUID userId) {
        active.remove(userId);
        deactivated.remove(userId);
        previous.remove(userId);
    }

    @Override
    public Optional<Contact> findActive(UUID userId) {
        return Optional.ofNullable(active.get(userId));
    }

    @Override
    public Optional<Contact> findDeactivated(UUID userId) {
        return Optional.ofNullable(deactivated.get(userId));
    }

    @Override
    public Optional<Contact> findPrevious(UUID userId) {
        return active.containsKey(userId) ? Optional.ofNullable(previous.get(userId)) : Optional.empty();
    }
}
