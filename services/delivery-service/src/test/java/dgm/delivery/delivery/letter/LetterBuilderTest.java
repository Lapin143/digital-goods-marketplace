package dgm.delivery.delivery.letter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.delivery.delivery.domain.ReleasedKeys;
import dgm.kit.secret.SecretValue;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Письмо с ключами (TC-603, TC-605, INV-19, INV-20): все значения в тексте по порядку, число значений равно числу в заказе, тема без переноса
 * строки, значения не попадают в печатное представление объектов.
 */
@Tag("TC-603")
@Tag("TC-605")
class LetterBuilderTest {

    private final LetterBuilder builder = new LetterBuilder();

    private static ReleasedKeys keys(String... values) {
        return new ReleasedKeys(java.util.Arrays.stream(values).map(SecretValue::of).toList());
    }

    @Test
    void everyKeyIsInTheTextInTheOrderOfIssue() {
        try (ReleasedKeys released = keys("AAAAA-11111", "BBBBB-22222", "CCCCC-33333")) {
            Letter letter = builder.build("Игра «Лабиринт»", 3, released);

            assertEquals("Ваши ключи: Игра «Лабиринт»", letter.subject());
            assertTrue(letter.text().contains("«Игра «Лабиринт»» (3 шт.)"), letter.text());
            int first = letter.text().indexOf("1. AAAAA-11111");
            int second = letter.text().indexOf("2. BBBBB-22222");
            int third = letter.text().indexOf("3. CCCCC-33333");
            assertTrue(first > 0 && second > first && third > second, letter.text());
        }
    }

    @Test
    void aLetterWithFewerOrMoreKeysThanInTheOrderIsNotBuilt() {
        try (ReleasedKeys fewer = keys("AAAAA-11111")) {
            KeyCountMismatchException e = assertThrows(KeyCountMismatchException.class, () -> builder.build("Игра", 2, fewer));

            assertEquals(2, e.expected());
            assertEquals(1, e.actual());
        }
        try (ReleasedKeys more = keys("AAAAA-11111", "BBBBB-22222", "CCCCC-33333")) {
            assertThrows(KeyCountMismatchException.class, () -> builder.build("Игра", 2, more));
        }
    }

    @Test
    void theSubjectNeverBreaksTheLineAndIsShortened() {
        try (ReleasedKeys released = keys("AAAAA-11111")) {
            Letter broken = builder.build("Игра\r\nBcc: evil@mail.example\tи ещё", 1, released);
            Letter longOne = builder.build("А".repeat(200), 1, released);

            assertFalse(broken.subject().contains("\n") || broken.subject().contains("\r") || broken.subject().contains("\t"), broken.subject());
            assertEquals("Ваши ключи: " + "А".repeat(79) + "…", longOne.subject());
        }
    }

    @Test
    void thePrintedFormsOfTheObjectsHideTheValues() {
        try (ReleasedKeys released = keys("AAAAA-11111", "BBBBB-22222")) {
            Letter letter = builder.build("Игра", 2, released);

            assertEquals("ReleasedKeys[2]", released.toString());
            assertFalse(released.values().toString().contains("AAAAA"), released.values().toString());
            assertFalse(letter.toString().contains("AAAAA") || letter.toString().contains("Игра"), letter.toString());
        }
    }

    @Test
    void closingTheKeysErasesTheValuesInMemory() {
        ReleasedKeys released = keys("AAAAA-11111", "BBBBB-22222");
        List<SecretValue> values = released.values();

        released.close();

        for (SecretValue value : values) {
            for (byte b : value.revealBytes()) {
                assertEquals(0, b);
            }
        }
    }
}
