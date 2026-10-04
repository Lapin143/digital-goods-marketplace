package dgm.kit.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProblemTypeTest {

    @Test
    void registryHasThirtyEightTypes() {
        assertEquals(38, ProblemType.values().length);
    }

    @Test
    void codesAreUniqueKebabCase() {
        Set<String> seen = new HashSet<>();
        for (ProblemType t : ProblemType.values()) {
            assertTrue(t.code().matches("[a-z]+(-[a-z]+)*"), t.code());
            assertTrue(seen.add(t.code()), "повтор кода " + t.code());
        }
    }

    @Test
    void statusesAreClientOrServerErrors() {
        Arrays.stream(ProblemType.values()).forEach(t -> assertTrue(t.status() >= 400 && t.status() <= 504, t.code()));
    }

    @Test
    void typeUriIsBasePlusCode() {
        assertEquals("https://api.marketplace.example/problems/insufficient-stock", ProblemType.INSUFFICIENT_STOCK.typeUri());
    }

    @Test
    void findsByCode() {
        assertEquals(ProblemType.RATE_LIMITED, ProblemType.byCode("rate-limited").orElseThrow());
        assertTrue(ProblemType.byCode("no-such-code").isEmpty());
    }

    @Test
    void keyStatuses() {
        assertEquals(401, ProblemType.UNAUTHENTICATED.status());
        assertEquals(403, ProblemType.FORBIDDEN.status());
        assertEquals(403, ProblemType.SECOND_FACTOR_REQUIRED.status());
        assertEquals(404, ProblemType.NOT_FOUND.status());
        assertEquals(422, ProblemType.VALIDATION_FAILED.status());
        assertEquals(429, ProblemType.RATE_LIMITED.status());
        assertEquals(503, ProblemType.DEPENDENCY_UNAVAILABLE.status());
    }
}
