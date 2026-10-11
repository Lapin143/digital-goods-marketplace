package dgm.platform.identity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.platform.identity.api.Contact;
import dgm.platform.identity.testing.InMemoryAccounts;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Контакты получателя для уведомлений: только активная, не обезличенная запись (INV-44). */
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

    @Test
    void contactPrintsOnlyTheIdentifier() {
        String printed = new Contact(ID, "anna@mail.example", "Анна").toString();

        assertFalse(printed.contains("anna@"), printed);
        assertFalse(printed.contains("Анна"), printed);
        assertTrue(printed.contains(ID.toString()), printed);
    }
}
