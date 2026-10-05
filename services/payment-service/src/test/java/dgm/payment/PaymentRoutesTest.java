package dgm.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dgm.kit.boot.testing.RouteCoverage;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Правила маршрутов сервиса (payment-service) согласованы и охватывают все обработчики контроллеров. */
class PaymentRoutesTest {

    private static final String ROUTES = "dgm/routes.json";

    @Test
    void everyHandlerHasARouteRule() {
        assertEquals(List.of(), RouteCoverage.handlersWithoutRule("dgm.payment", ROUTES));
    }

    @Test
    void routeRulesAreConsistent() {
        assertEquals(List.of(), RouteCoverage.policyProblems(ROUTES, "payment-service"));
    }
}
