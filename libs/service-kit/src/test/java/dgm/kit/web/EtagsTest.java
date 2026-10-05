package dgm.kit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EtagsTest {

    @Test
    void etagIsQuotedAndStable() {
        String tag = Etags.of("{\"items\":[]}");
        assertTrue(tag.matches("^\"[A-Za-z0-9_-]{22}\"$"), tag);
        assertEquals(tag, Etags.of("{\"items\":[]}"));
    }

    @Test
    void differentBodiesGiveDifferentTags() {
        assertNotEquals(Etags.of("a"), Etags.of("b"));
    }

    @Test
    void missingOrBlankHeaderNeverMatches() {
        String tag = Etags.of("a");
        assertFalse(Etags.matches(null, tag));
        assertFalse(Etags.matches("", tag));
        assertFalse(Etags.matches("   ", tag));
    }

    @Test
    void starMatchesAnything() {
        assertTrue(Etags.matches("*", Etags.of("a")));
    }

    @Test
    void exactAndListedTagsMatch() {
        String tag = Etags.of("a");
        assertTrue(Etags.matches(tag, tag));
        assertTrue(Etags.matches("\"other\", " + tag + " , \"third\"", tag));
    }

    @Test
    void weakValidatorMatchesWeakly() {
        String tag = Etags.of("a");
        assertTrue(Etags.matches("W/" + tag, tag));
    }

    @Test
    void otherTagDoesNotMatch() {
        assertFalse(Etags.matches(Etags.of("b"), Etags.of("a")));
    }
}
