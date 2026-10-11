package dgm.platform.identity.service;

import dgm.platform.identity.api.Contact;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.repository.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link UserContacts} поверх учётных записей: одна короткая выборка без блокировок, соединение роли {@code app_identity} не удерживается. */
@Component
public final class AccountContacts implements UserContacts {

    private final UserAccountRepository accounts;

    public AccountContacts(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<Contact> findActive(UUID userId) {
        return accounts.find(userId).filter(UserAccount::active).filter(account -> !account.anonymized())
                .map(account -> new Contact(account.id(), account.email(), account.name()));
    }
}
