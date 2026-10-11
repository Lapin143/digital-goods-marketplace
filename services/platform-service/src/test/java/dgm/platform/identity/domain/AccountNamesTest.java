package dgm.platform.identity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountNamesTest {

    private static final UUID ID = UUID.fromString("0199e0a0-0000-4000-8000-0000000000a1");

    @Test
    void emailIsNormalizedLikeTheColumnConstraint() {
        assertEquals("ivan@mail.example", AccountNames.email("  Ivan@Mail.EXAMPLE ", null, ID));
    }

    @Test
    void usernameServesWhenProfileHasNoEmail() {
        assertEquals("ivan@mail.example", AccountNames.email(null, "Ivan@Mail.example", ID));
        assertEquals("ivan@mail.example", AccountNames.email("", "ivan@mail.example", ID));
    }

    @Test
    void serviceAddressIsUsedWhenNothingLooksLikeAnAddress() {
        assertEquals("user-" + ID + "@invalid", AccountNames.email(null, "service-account-x", ID));
        assertEquals("user-" + ID + "@invalid", AccountNames.email("@host", null, ID));
        assertEquals("user-" + ID + "@invalid", AccountNames.email("a@", "bad", ID));
    }

    @Test
    void tooLongAddressIsReplacedBecauseTheColumnRejectsIt() {
        String longAddress = "a".repeat(250) + "@b.co";
        assertEquals("user-" + ID + "@invalid", AccountNames.email(longAddress, null, ID));
    }

    @Test
    void profileNameWinsThenLocalPartOfTheAddress() {
        assertEquals("Иван Петров", AccountNames.displayName("  Иван Петров ", "ivan@mail.example", "ivan@mail.example"));
        assertEquals("ivan", AccountNames.displayName(null, "ivan@mail.example", "ivan@mail.example"));
        assertEquals("ivan", AccountNames.displayName("   ", "other", "ivan@mail.example"));
        assertEquals("login", AccountNames.displayName(null, "login", null));
    }

    @Test
    void emptyEverythingGivesAGenericName() {
        assertEquals("Пользователь", AccountNames.displayName(null, null, null));
        assertEquals("Пользователь", AccountNames.displayName("", "", ""));
    }

    @Test
    void nameIsCutAtTheColumnLimitWithoutBreakingSurrogatePairs() {
        String emoji = "😀".repeat(AccountNames.NAME_MAX + 5);
        String name = AccountNames.displayName(emoji, null, null);
        assertEquals(AccountNames.NAME_MAX, name.codePointCount(0, name.length()));
        assertTrue(name.startsWith("😀"));
    }
}
