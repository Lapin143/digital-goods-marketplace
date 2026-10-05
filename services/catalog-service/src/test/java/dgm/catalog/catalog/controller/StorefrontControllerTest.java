package dgm.catalog.catalog.controller;

import static dgm.catalog.catalog.FakeProducts.id;
import static dgm.catalog.catalog.FakeProducts.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dgm.catalog.catalog.FakeProducts;
import dgm.catalog.catalog.service.StorefrontService;
import dgm.kit.problem.ProblemAdvice;
import dgm.kit.web.PageCursor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code listStorefrontProducts}: контракт ответа, кэширование, проверка параметров и постраничный обход. */
class StorefrontControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private FakeProducts products;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        products = new FakeProducts().with(item(1, T0), item(2, T0.plusSeconds(60)), item(3, T0.plusSeconds(120)));
        mvc = MockMvcBuilders.standaloneSetup(new StorefrontController(new StorefrontService(products)))
                .setControllerAdvice(new ProblemAdvice()).build();
    }

    private MvcResult ok(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
    }

    @Test
    void defaultPageShowsNewestFirstWithCachingHeaders() throws Exception {
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=30"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].productId").value(id(3).toString()))
                .andExpect(jsonPath("$.items[0].price.amount").value(300_00))
                .andExpect(jsonPath("$.items[0].price.currency").value("RUB"))
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andExpect(jsonPath("$.page.limit").value(20))
                .andExpect(jsonPath("$.page.nextCursor").doesNotExist());
        assertEquals(20, products.lastFilter().limit());
    }

    @Test
    void sameDataGivesSameEtagAndConditionalRequestIsNotModified() throws Exception {
        String etag = ok("/api/v1/products").getResponse().getHeader(HttpHeaders.ETAG);
        assertNotNull(etag);
        assertEquals(etag, ok("/api/v1/products").getResponse().getHeader(HttpHeaders.ETAG));
        mvc.perform(get("/api/v1/products").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));
    }

    @Test
    void changedDataIsNotAnsweredWithNotModified() throws Exception {
        String etag = ok("/api/v1/products").getResponse().getHeader(HttpHeaders.ETAG);
        products.with(item(4, T0.plusSeconds(180)));
        MvcResult after = mvc.perform(get("/api/v1/products").header(HttpHeaders.IF_NONE_MATCH, etag)).andExpect(status().isOk()).andReturn();
        assertNotEquals(etag, after.getResponse().getHeader(HttpHeaders.ETAG));
    }

    @Test
    void cursorFromPreviousPageContinuesTheList() throws Exception {
        String firstBody = ok("/api/v1/products?limit=2").getResponse().getContentAsString();
        List<String> firstIds = JsonPath.read(firstBody, "$.items[*].productId");
        assertEquals(List.of(id(3).toString(), id(2).toString()), firstIds);
        String cursor = JsonPath.read(firstBody, "$.page.nextCursor");
        assertNotNull(cursor);

        String secondBody = ok("/api/v1/products?limit=2&cursor=" + cursor).getResponse().getContentAsString();
        List<String> secondIds = JsonPath.read(secondBody, "$.items[*].productId");
        assertEquals(List.of(id(1).toString()), secondIds);
        Object nextOfLast = JsonPath.read(secondBody, "$.page.nextCursor");
        assertNull(nextOfLast);
    }

    @Test
    void filtersAreParsedAndPassedToTheService() throws Exception {
        ok("/api/v1/products?q=Товар&productType=game_key,gift_card&platform=Steam&region=RU&priceFrom=100&priceTo=500&limit=5");
        var filter = products.lastFilter();
        assertEquals(Optional.of("Товар"), filter.titleContains());
        assertEquals(Optional.of(List.of("game_key", "gift_card")), filter.productTypes());
        assertEquals(Optional.of("Steam"), filter.platform());
        assertEquals(Optional.of("RU"), filter.region());
        assertEquals(Optional.of(100L), filter.priceFrom());
        assertEquals(Optional.of(500L), filter.priceTo());
        assertEquals(5, filter.limit());
    }

    @Test
    void reversedPriceRangeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/products?priceFrom=500&priceTo=100"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].parameter").value("priceFrom"))
                .andExpect(jsonPath("$.errors[0].code").value("out_of_range"));
    }

    @Test
    void unknownParameterIsRejected() throws Exception {
        mvc.perform(get("/api/v1/products?color=red"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("color"))
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
    }

    @Test
    void unknownProductTypeIsRejected() throws Exception {
        mvc.perform(get("/api/v1/products?productType=game_key,toaster"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("productType"))
                .andExpect(jsonPath("$.errors[0].code").value("unknown_value"));
    }

    @Test
    void limitAboveMaximumIsRejected() throws Exception {
        mvc.perform(get("/api/v1/products?limit=101"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("limit"))
                .andExpect(jsonPath("$.errors[0].code").value("out_of_range"));
    }

    @Test
    void lowercaseRegionIsRejected() throws Exception {
        mvc.perform(get("/api/v1/products?region=ru"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("region"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid_format"));
    }

    @Test
    void cursorWithGarbageInsideIsRejected() throws Exception {
        String cursor = PageCursor.encode(Map.of("p", "вчера", "i", "не-uuid"));
        mvc.perform(get("/api/v1/products?cursor=" + cursor))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].parameter").value("cursor"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid_format"));
    }

    @Test
    void allErrorsAreReportedTogether() throws Exception {
        mvc.perform(get("/api/v1/products?limit=0&region=ru&foo=1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(3));
    }
}
