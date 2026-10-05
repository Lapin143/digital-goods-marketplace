package dgm.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dgm.kit.boot.testing.RouteCoverage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Правила маршрутов сервиса (catalog-service) согласованы и охватывают все обработчики контроллеров. */
class CatalogRoutesTest {

    private static final String ROUTES = "dgm/routes.json";

    @Test
    void everyHandlerHasARouteRule() {
        assertEquals(List.of(), RouteCoverage.handlersWithoutRule("dgm.catalog", ROUTES));
    }

    @Test
    void routeRulesAreConsistent() {
        assertEquals(List.of(), RouteCoverage.policyProblems(ROUTES, "catalog-service"));
    }
}
