package dgm.catalog.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dgm.catalog.catalog.FakeProducts;
import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.seller.FakeSellerIntegrations;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("TC-325")
class ProductCardServiceTest {

    private static final ProductCard BLOCKED = new ProductCard(UUID.fromString("0199e0a0-0000-4000-8000-000000000207"),
            UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1"), "Заблокированный товар", "blocked", 99_900L, "RUB", "key", "game_key");

    @Test
    void cardInAnyStatusIsReturned() {
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(BLOCKED), new FakeSellerIntegrations());
        assertEquals(BLOCKED, service.get(BLOCKED.id()));
    }

    @Test
    void unknownProductIsNotFound() {
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(BLOCKED), new FakeSellerIntegrations());
        UUID unknown = UUID.fromString("0199e0a0-0000-4000-8000-000000000999");
        ProblemException e = assertThrows(ProblemException.class, () -> service.get(unknown));
        assertEquals(ProblemType.NOT_FOUND, e.type());
    }

    // ------------------------------------------------------------------ товар по API без подключения (R2, Р-184, Р-187)

    private static final UUID API_SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2");

    private static ProductCard card(String issuance, String status) {
        return new ProductCard(UUID.fromString("0199e0a0-0000-4000-8000-000000000208"), API_SELLER, "Лунная тропа: ключ по API", status, 149_900L,
                "RUB", issuance, "game_key");
    }

    @Test
    void aPublishedApiProductWithoutAnActiveIntegrationIsReportedAsBlockedSoNoOrderIsPlaced() {
        ProductCard published = card("seller_api", "published");
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(published), new FakeSellerIntegrations());
        ProductCard seen = service.get(published.id());
        assertEquals("blocked", seen.status());
        assertEquals(new ProductCard(published.id(), API_SELLER, published.title(), "blocked", 149_900L, "RUB", "seller_api", "game_key"), seen);
    }

    @Test
    void aPublishedApiProductWithAnActiveIntegrationKeepsItsStatus() {
        ProductCard published = card("seller_api", "published");
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(published), new FakeSellerIntegrations().connect(API_SELLER));
        assertEquals(published, service.get(published.id()));
    }

    @Test
    void disconnectingTheSystemTakesTheProductOutOfSaleAndConnectingBringsItBack() {
        ProductCard published = card("seller_api", "published");
        FakeSellerIntegrations integrations = new FakeSellerIntegrations().connect(API_SELLER);
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(published), integrations);
        assertEquals("published", service.get(published.id()).status());
        integrations.disconnect(API_SELLER);
        assertEquals("blocked", service.get(published.id()).status());
        integrations.connect(API_SELLER);
        assertEquals("published", service.get(published.id()).status());
    }

    @Test
    void onlyAPublishedApiProductIsRewrittenAndOtherStatusesNeverAskForTheIntegration() {
        FakeSellerIntegrations integrations = new FakeSellerIntegrations();
        for (String status : new String[] {"draft", "on_moderation", "rejected", "blocked", "archived"}) {
            ProductCard other = card("seller_api", status);
            ProductCardService service = new ProductCardService(new FakeProducts().withCard(other), integrations);
            assertEquals(status, service.get(other.id()).status());
        }
        assertEquals(0, integrations.checks());
    }

    @Test
    void aKeyPoolProductNeverAsksForTheIntegration() {
        FakeSellerIntegrations integrations = new FakeSellerIntegrations();
        ProductCard published = card("key_pool", "published");
        ProductCardService service = new ProductCardService(new FakeProducts().withCard(published), integrations);
        assertEquals(published, service.get(published.id()));
        assertEquals(0, integrations.checks());
        assertNotEquals("blocked", service.get(published.id()).status());
    }
}
