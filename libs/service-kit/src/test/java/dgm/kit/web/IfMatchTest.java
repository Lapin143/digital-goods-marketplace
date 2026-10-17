package dgm.kit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class IfMatchTest {

    private static MockHttpServletRequest withHeader(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/x");
        if (value != null) {
            request.addHeader("If-Match", value);
        }
        return request;
    }

    @Test
    void etagIsTheVersionInQuotes() {
        assertEquals("\"7\"", IfMatch.etag(7));
    }

    @Test
    void strongVersionIsRead() {
        assertEquals(7, IfMatch.version(withHeader("\"7\"")));
        assertEquals(12, IfMatch.version(withHeader("  \"12\" ")));
    }

    @Test
    void missingHeaderIsPreconditionRequired() {
        assertEquals(ProblemType.PRECONDITION_REQUIRED, assertThrows(ProblemException.class, () -> IfMatch.version(withHeader(null))).type());
        assertEquals(ProblemType.PRECONDITION_REQUIRED, assertThrows(ProblemException.class, () -> IfMatch.version(withHeader("  "))).type());
    }

    @Test
    void anythingButAStrongVersionIsBadRequest() {
        for (String value : new String[] {"7", "W/\"7\"", "\"abc\"", "*", "\"-1\"", "\"1234567890\"", "\"7\", \"8\""}) {
            assertEquals(ProblemType.BAD_REQUEST, assertThrows(ProblemException.class, () -> IfMatch.version(withHeader(value))).type(), value);
        }
    }
}
