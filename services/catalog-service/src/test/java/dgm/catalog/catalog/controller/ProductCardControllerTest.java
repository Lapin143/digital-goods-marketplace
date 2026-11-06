package dgm.catalog.catalog.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dgm.catalog.catalog.FakeProducts;
import dgm.catalog.catalog.domain.ProductCard;
import dgm.catalog.catalog.seller.FakeSellerIntegrations;
import dgm.catalog.catalog.service.ProductCardService;
import dgm.kit.problem.ProblemAdvice;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code getProductCard}: внутренний вызов сервиса заказов. */
@Tag("TC-325")
class ProductCardControllerTest {

    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-4000-8000-000000000201");
    private static final ProductCard CARD = new ProductCard(PRODUCT, UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1"), "Игра", "published",
            149_900L, "RUB", "key", "game_key");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = mvcFor(CARD, new FakeSellerIntegrations());
    }

    private static MockMvc mvcFor(ProductCard card, FakeSellerIntegrations integrations) {
        return MockMvcBuilders.standaloneSetup(new ProductCardController(new ProductCardService(new FakeProducts().withCard(card), integrations)))
                .setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void existingProductIsReturnedWithoutCaching() throws Exception {
        mvc.perform(get("/internal/v1/products/" + PRODUCT))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.productId").value(PRODUCT.toString()))
                .andExpect(jsonPath("$.sellerId").value(CARD.sellerId().toString()))
                .andExpect(jsonPath("$.status").value("published"))
                .andExpect(jsonPath("$.price.amount").value(149_900))
                .andExpect(jsonPath("$.price.currency").value("RUB"))
                .andExpect(jsonPath("$.issuanceMethod").value("key"))
                .andExpect(jsonPath("$.productType").value("game_key"));
    }

    /** Шаг 9 Ф5, находка F5-16: опубликованный товар по API без действующего подключения для заказов «заблокирован», с подключением виден как есть. */
    @Test
    void aPublishedApiProductReadsAsBlockedWhileTheSellersSystemIsNotConnected() throws Exception {
        ProductCard api = new ProductCard(PRODUCT, CARD.sellerId(), "Игра", "published", 149_900L, "RUB", "seller_api", "game_key");
        FakeSellerIntegrations integrations = new FakeSellerIntegrations();

        mvcFor(api, integrations).perform(get("/internal/v1/products/" + PRODUCT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("blocked"))
                .andExpect(jsonPath("$.issuanceMethod").value("seller_api"));
        mvcFor(api, integrations.connect(CARD.sellerId())).perform(get("/internal/v1/products/" + PRODUCT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void unknownProductIsNotFound() throws Exception {
        mvc.perform(get("/internal/v1/products/0199e0a0-0000-4000-8000-000000000999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void malformedIdIsBadRequest() throws Exception {
        for (String bad : new String[] {"abc", "0199E0A0-0000-4000-8000-000000000201", "0199e0a0-0000-4000-8000-00000000020"}) {
            mvc.perform(get("/internal/v1/products/" + bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("bad-request"));
        }
    }
}
