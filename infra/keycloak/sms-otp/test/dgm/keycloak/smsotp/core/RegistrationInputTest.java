package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RegistrationInputTest {

    @Test
    void validFormIsNormalized() {
        RegistrationInput.Result result = RegistrationInput.check("  Buyer@Example.COM ", "long-password-123", "long-password-123");
        assertTrue(result.valid());
        assertEquals("buyer@example.com", result.email());
    }

    @Test
    void problemsDoNotDependOnWhetherTheAddressIsTaken() {
        // Проверка получает только поля формы: занятость адреса ей неизвестна, значит, и ответ на неё не влияет
        RegistrationInput.Result bad = RegistrationInput.check("not-an-email", "x", "y");
        assertEquals(List.of(new RegistrationInput.Problem("email", "invalidEmailMessage"),
                new RegistrationInput.Problem("password-confirm", "invalidPasswordConfirmMessage")), bad.problems());
    }

    @Test
    void emptyPasswordAndOversizedValues() {
        assertEquals(List.of(new RegistrationInput.Problem("password", "missingPasswordMessage")),
                RegistrationInput.check("a@b.example", "", "").problems());
        assertEquals(List.of(new RegistrationInput.Problem("password", "missingPasswordMessage")),
                RegistrationInput.check("a@b.example", null, null).problems());
        assertEquals(List.of(new RegistrationInput.Problem("password", "invalidPasswordMessage")),
                RegistrationInput.check("a@b.example", "p".repeat(129), "p".repeat(129)).problems());
        assertEquals("invalidEmailMessage", RegistrationInput.check("a".repeat(256) + "@b.example", "pw", "pw").problems().get(0).messageKey());
    }

    @Test
    void emailShapes() {
        for (String ok : new String[] {"a@b.co", "first.last+tag@mail.example.org", "ИМЯ@пример.рф"}) {
            assertTrue(RegistrationInput.check(ok, "pw", "pw").valid(), ok);
        }
        for (String bad : new String[] {"", "a", "a@", "@b.co", "a@b", "a b@c.co", "a@b@c.co", "a@b.c"}) {
            assertEquals("invalidEmailMessage", RegistrationInput.check(bad, "pw", "pw").problems().get(0).messageKey(), bad);
        }
    }
}
