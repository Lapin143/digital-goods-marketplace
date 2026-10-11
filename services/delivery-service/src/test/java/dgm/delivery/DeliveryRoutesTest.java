package dgm.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dgm.kit.boot.testing.RouteCoverage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Правила маршрутов сервиса (delivery-service) согласованы и охватывают все обработчики контроллеров. */
class DeliveryRoutesTest {

    private static final String ROUTES = "dgm/routes.json";

    @Test
    void everyHandlerHasARouteRule() {
        assertEquals(List.of(), RouteCoverage.handlersWithoutRule("dgm.delivery", ROUTES));
    }

    /** Каждая операция OpenAPI записана в реестре готовности; готовые имеют обработчик, остальные его ещё не имеют (находка 5). */
    @Test
    void operationLedgerMatchesCodeAndContract() {
        assertEquals(List.of(), RouteCoverage.ledgerProblems("dgm.delivery", ROUTES, "dgm/operations.txt"));
    }

    @Test
    void routeRulesAreConsistent() {
        assertEquals(List.of(), RouteCoverage.policyProblems(ROUTES, "delivery-service"));
    }
}
