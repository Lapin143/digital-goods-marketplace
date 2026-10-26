package dgm.platform.identity.service;

import dgm.platform.identity.api.AccountEmails;
import dgm.platform.identity.api.AdminRecipients;
import dgm.platform.identity.api.Contact;
import dgm.platform.identity.api.SellerRecipients;
import dgm.platform.identity.api.UserContacts;
import dgm.platform.identity.domain.Roles;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.domain.UserFilter;
import dgm.platform.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link UserContacts}, {@link AdminRecipients}, {@link AccountEmails} и {@link SellerRecipients} поверх учётных записей: одна короткая выборка без блокировок, соединение роли
 * {@code app_identity} не удерживается.
 */
@Component
public final class AccountContacts implements UserContacts, AdminRecipients, AccountEmails, SellerRecipients {

    private final UserAccountRepository accounts;
    private final Clock clock;

    public AccountContacts(UserAccountRepository accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    @Override
    public Optional<Contact> findActive(UUID userId) {
        return accounts.find(userId).filter(UserAccount::active).filter(account -> !account.anonymized())
                .map(account -> new Contact(account.id(), account.email(), account.name()));
    }

    @Override
    public Optional<Contact> findPrevious(UUID userId) {
        return accounts.find(userId).filter(UserAccount::active).filter(account -> !account.anonymized())
                .flatMap(account -> accounts.previousEmail(userId, clock.instant()).map(email -> new Contact(account.id(), email, account.name())));
    }

    @Override
    public Optional<Contact> findDeactivated(UUID userId) {
        return accounts.find(userId).filter(account -> UserAccount.DEACTIVATED.equals(account.status())).filter(account -> !account.anonymized())
                .map(account -> new Contact(account.id(), account.email(), account.name()));
    }

    @Override
    public Optional<UUID> ownerOf(UUID sellerId) {
        return accounts.findBySellerId(sellerId).filter(account -> !account.anonymized()).map(UserAccount::id);
    }

    @Override
    public List<UUID> activeAdminIds() {
        UserFilter filter = new UserFilter(Optional.of(Roles.ADMIN), Optional.of(UserAccount.ACTIVE), Optional.empty(), MAX, Optional.empty());
        return accounts.list(filter).stream().limit(MAX).filter(UserAccount::active).filter(account -> !account.anonymized())
                .map(UserAccount::id).toList();
    }

    @Override
    public boolean isUnavailable(String email) {
        return accounts.emailExists(email);
    }
}
