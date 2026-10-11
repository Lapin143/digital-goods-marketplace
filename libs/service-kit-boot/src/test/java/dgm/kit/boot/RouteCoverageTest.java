package dgm.kit.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.RouteCoverage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Проверка самой сверки маршрутов: она должна находить расхождения, иначе сервисные тесты ничего не защищают. */
class RouteCoverageTest {

    private static final String SAMPLE = "dgm.kit.boot.sample";

    @Test
    void handlersAreFoundWithPrefixAndPathVariables() {
        List<String> found = RouteCoverage.handlers(SAMPLE).stream().map(h -> h.method() + " " + h.path()).sorted().toList();
        assertEquals(List.of("GET /api/v1/hidden", "GET /api/v1/things", "POST /api/v1/things/{thingId}/close"), found);
    }

    @Test
    void handlerWithoutRuleIsReported() {
        List<String> missing = RouteCoverage.handlersWithoutRule(SAMPLE, "dgm/sample-routes.json");
        assertEquals(1, missing.size());
        assertTrue(missing.get(0).startsWith("GET /api/v1/hidden"), missing.toString());
    }

    @Test
    void consistentPolicyHasNoProblems() {
        assertEquals(List.of(), RouteCoverage.policyProblems("dgm/sample-routes.json", "sample-service"));
    }

    @Test
    void wrongServiceNameIsReported() {
        List<String> problems = RouteCoverage.policyProblems("dgm/sample-routes.json", "catalog-service");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("sample-service"), problems.toString());
    }

    @Test
    void inconsistentRoutesAreAllReported() {
        List<String> problems = RouteCoverage.policyProblems("dgm/broken-routes.json", "other-service");
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("/internal/v1/open") && p.contains("без списка вызывающих")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("/internal/v1/user") && p.contains("требует токен")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("/v1/lost") && p.contains("не начинается")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("/api/v1/mixed") && p.contains("со списком вызывающих")));
    }
    // --- реестр готовности операций (находка 5 плана Ф4) -------------------------------------------------------------------------

    @Test
    void consistentLedgerHasNoProblems() {
        assertEquals(List.of(), RouteCoverage.ledgerProblems(SAMPLE, "dgm/sample-routes.json", "dgm/sample-operations.txt"));
    }

    @Test
    void everyKindOfLedgerMismatchIsReported() {
        List<String> problems = RouteCoverage.ledgerProblems(SAMPLE, "dgm/sample-routes.json", "dgm/broken-operations.txt");
        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("listThings") && p.contains("переведите её в done")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("closeThing") && p.contains("дважды")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("getThing") && p.contains("обработчика в контроллерах нет")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("phantom") && p.contains("нет в OpenAPI")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("lonely") && p.contains("нужны имя операции и статус")), problems.toString());
    }

    @Test
    void operationMissingFromTheLedgerIsReported() {
        List<String> problems = RouteCoverage.ledgerProblems(SAMPLE, "dgm/sample-routes.json", "dgm/missing-operations.txt");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("getThing") && problems.get(0).contains("не записана в реестре"), problems.toString());
    }

    @Test
    void unknownStatusIsReported() {
        List<String> problems = RouteCoverage.ledgerProblems(SAMPLE, "dgm/sample-routes.json", "dgm/soon-operations.txt");
        assertTrue(problems.stream().anyMatch(p -> p.contains("getThing") && p.contains("неизвестный статус")), problems.toString());
    }

    @Test
    void missingLedgerFileIsReported() {
        List<String> problems = RouteCoverage.ledgerProblems(SAMPLE, "dgm/sample-routes.json", "dgm/no-such-operations.txt");
        assertTrue(problems.stream().anyMatch(p -> p.contains("Нет реестра операций")), problems.toString());
    }

    /** Заведомо пустой сервис: ни одного контроллера, а реестр обещает готовые операции. Так проверка краснеет, пока шаги не реализованы. */
    @Test
    void serviceWithoutControllersTurnsRedWhenTheLedgerPromisesOperations() {
        List<String> problems = RouteCoverage.ledgerProblems("dgm.kit.boot.empty", "dgm/sample-routes.json", "dgm/sample-operations.txt");
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.stream().allMatch(p -> p.contains("обработчика в контроллерах нет")), problems.toString());
    }
}
