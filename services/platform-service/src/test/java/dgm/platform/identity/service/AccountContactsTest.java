package dgm.platform.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.identity.api.AdminRecipients;
import dgm.platform.identity.api.Contact;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Контакты получателя для уведомлений: только активная, не обезличенная запись (INV-44); получатели оповещений администратору (Р-153). */
class AccountContactsTest {

    private static final UUID ID = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");
    private static final Instant REGISTERED = Instant.parse("2026-10-01T12:00:00Z");

    private InMemoryAccounts accounts;
    private AccountContacts contacts;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccounts();
        contacts = new AccountContacts(accounts);
    }

    @Test
    void activeAccountGivesItsAddressAndName() {
        accounts.add(ID, "anna@mail.example", "buyer", REGISTERED);

        Contact contact = contacts.findActive(ID).orElseThrow();

        assertEquals(ID, contact.userId());
        assertEquals("anna@mail.example", contact.email());
        assertEquals("Имя 0199", contact.name());
    }

    @Test
    void unknownAccountGivesNothing() {
        assertTrue(contacts.findActive(UUID.randomUUID()).isEmpty());
    }

    @Test
    void deactivatedAccountGivesNothing() {
        accounts.add(ID, "anna@mail.example", "buyer", REGISTERED);

        accounts.deactivate(ID, REGISTERED.plusSeconds(60));

        assertTrue(contacts.findActive(ID).isEmpty());
    }

    @Test
    void anonymizedAccountGivesNothing() {
        accounts.add(ID, "anna@mail.example", "buyer", REGISTERED);
        accounts.deactivate(ID, REGISTERED.plusSeconds(60));
        accounts.anonymize(ID);

        assertTrue(contacts.findActive(ID).isEmpty());
    }

    @Tag("TC-610")
    @Test
    void adminRecipientsAreTheActiveAdminsNewestFirst() {
        UUID older = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
        UUID newer = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2");
        UUID gone = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b3");
        accounts.add(older, "root@mail.example", "admin", REGISTERED);
        accounts.add(newer, "second@mail.example", "admin", REGISTERED.plusSeconds(10));
        accounts.add(gone, "left@mail.example", "admin", REGISTERED.plusSeconds(20));
        accounts.add(ID, "anna@mail.example", "buyer", REGISTERED.plusSeconds(30));
        accounts.deactivate(gone, REGISTERED.plusSeconds(60));

        assertEquals(List.of(newer, older), contacts.activeAdminIds());
    }

    @Tag("TC-610")
    @Test
    void adminRecipientsAreEmptyWithoutAdmins() {
        accounts.add(ID, "anna@mail.example", "buyer", REGISTERED);

        assertTrue(contacts.activeAdminIds().isEmpty());
    }

    @Tag("TC-610")
    @Test
    void adminRecipientsAreCappedAtTheLimit() {
        for (int i = 0; i < AdminRecipients.MAX + 5; i++) {
            accounts.add(new UUID(1L, i), "admin" + i + "@mail.example", "admin", REGISTERED.plusSeconds(i));
        }

        assertEquals(AdminRecipients.MAX, contacts.activeAdminIds().size());
    }

    @Test
    void contactPrintsOnlyTheIdentifier() {
        String printed = new Contact(ID, "anna@mail.example", "Анна").toString();

        assertFalse(printed.contains("anna@"), printed);
        assertFalse(printed.contains("Анна"), printed);
        assertTrue(printed.contains(ID.toString()), printed);
    }
}
