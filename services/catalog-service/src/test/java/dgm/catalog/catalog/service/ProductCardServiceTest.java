package dgm.catalog.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.catalog.catalog.FakeProducts;
import dgm.catalog.catalog.domain.ProductCard;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductCardServiceTest {

    private static final ProductCard BLOCKED = new ProductCard(UUID.fromString("0199e0a0-0000-4000-8000-000000000207"),
            UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1"), "Заблокированный товар", "blocked", 99_900L, "RUB", "key", "game_key");

    @Test
    void cardInAnyStatusIsReturned() {
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(BLOCKED));
        assertEquals(BLOCKED, service.get(BLOCKED.id()));
    }

    @Test
    void unknownProductIsNotFound() {
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(BLOCKED));
        UUID unknown = UUID.fromString("0199e0a0-0000-4000-8000-000000000999");
        ProblemException e = assertThrows(ProblemException.class, () -> service.get(unknown));
        assertEquals(ProblemType.NOT_FOUND, e.type());
    }
}
