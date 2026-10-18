package dgm.catalog.catalog.controller;

import static dgm.catalog.catalog.FakeProducts.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dgm.catalog.catalog.FakeProducts;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.domain.StorefrontCard;
import dgm.catalog.catalog.service.StorefrontProductService;
import dgm.catalog.catalog.service.StorefrontService;
import dgm.kit.problem.ProblemAdvice;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code getStorefrontProduct}: контракт карточки, ETag и 304, 404 для не опубликованного, предел количества в заказе. */
@Tag("TC-318")
@Tag("TC-319")
@Tag("TC-320")
class StorefrontProductControllerTest {

    private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    private FakeProducts products;
    private MemoryCache memory;
    private MockMvc mvc;

    private static StorefrontCard card(int n, int available) {
        return new StorefrontCard(id(n), SELLER, "Лунная тропа " + n, "Приключенческая игра", "game_key", "Steam", List.of("RU", "KZ"), 149_900L,
                "RUB", available);
    }

    @BeforeEach
    void setUp() {
        products = new FakeProducts().withPublished(card(1, 25)).withPublished(card(2, 3)).withPublished(card(3, 0));
        memory = new MemoryCache();
        mvc = MockMvcBuilders.standaloneSetup(new StorefrontController(new StorefrontService(products),
                new StorefrontProductService(products, memory.cache()))).setControllerAdvice(new ProblemAdvice()).build();
    }

    @Test
    void cardMatchesTheContractFields() throws Exception {
        mvc.perform(get("/api/v1/products/" + id(1)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=30"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.productId").value(id(1).toString()))
                .andExpect(jsonPath("$.sellerId").value(SELLER.toString()))
                .andExpect(jsonPath("$.title").value("Лунная тропа 1"))
                .andExpect(jsonPath("$.description").value("Приключенческая игра"))
                .andExpect(jsonPath("$.productType").value("game_key"))
                .andExpect(jsonPath("$.platform").value("Steam"))
                .andExpect(jsonPath("$.activationRegions[0]").value("RU"))
                .andExpect(jsonPath("$.price.amount").value(149_900))
                .andExpect(jsonPath("$.price.currency").value("RUB"))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.maxOrderQuantity").value(10));
    }

    @Test
    void maxOrderQuantityFollowsTheStockUpToTen() throws Exception {
        mvc.perform(get("/api/v1/products/" + id(2))).andExpect(jsonPath("$.maxOrderQuantity").value(3)).andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    void aZeroStockStillShowsTheCardButNothingCanBeBought() throws Exception {
        mvc.perform(get("/api/v1/products/" + id(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inStock").value(false))
                .andExpect(jsonPath("$.maxOrderQuantity").value(0));
    }

    @Test
    void aConditionalRequestWithTheCurrentEtagIsNotModified() throws Exception {
        String etag = mvc.perform(get("/api/v1/products/" + id(1))).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(etag);
        mvc.perform(get("/api/v1/products/" + id(1)).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));
    }

    @Test
    void anOutdatedEtagGetsTheFullCard() throws Exception {
        mvc.perform(get("/api/v1/products/" + id(1)).header(HttpHeaders.IF_NONE_MATCH, "\"old\"")).andExpect(status().isOk());
    }

    @Test
    void aProductThatIsNotPublishedIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/products/" + id(9)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void aMalformedIdIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/products/не-uuid")).andExpect(status().isBadRequest());
        // В идентификаторе из цифр верхний регистр ничего не меняет, поэтому берём UUID с буквами
        mvc.perform(get("/api/v1/products/0199E0A0-0000-7000-8000-0000000000AA")).andExpect(status().isBadRequest());
    }

    @Test
    void theCardIsServedFromTheCacheAfterTheFirstRead() throws Exception {
        mvc.perform(get("/api/v1/products/" + id(1))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + id(1))).andExpect(status().isOk());
        assertEquals(1, products.cardReads(), "вторая карточка пришла из кэша, база не читалась");
    }
}
