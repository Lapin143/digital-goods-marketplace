package dgm.catalog.catalog.service;

import static dgm.catalog.catalog.FakeProducts.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.catalog.catalog.FakeProducts;
import dgm.catalog.catalog.cache.MemoryCache;
import dgm.catalog.catalog.cache.ProductCache;
import dgm.catalog.catalog.domain.CacheSettings;
import dgm.catalog.catalog.domain.StorefrontCard;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Кэш карточки витрины: порядок «кэш, база», срок жизни, сброс и работа без Redis (NFT-1.0). */
@Tag("TC-319")
class StorefrontProductServiceTest {

    private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    private FakeProducts products;
    private MemoryCache memory;
    private StorefrontProductService service;

    private static StorefrontCard card(int n) {
        return new StorefrontCard(id(n), SELLER, "Товар " + n, "Описание", "game_key", "Steam", List.of(), 100_00L, "RUB", 5);
    }

    @BeforeEach
    void setUp() {
        products = new FakeProducts().withPublished(card(1));
        memory = new MemoryCache();
        service = new StorefrontProductService(products, memory.cache());
    }

    private String body(UUID id) {
        return service.body(id, c -> "{\"title\":\"" + c.title() + "\"}");
    }

    @Test
    void aMissReadsTheDatabaseAndFillsTheCacheWithTheConfiguredTtl() {
        assertEquals("{\"title\":\"Товар 1\"}", body(id(1)));
        assertTrue(memory.has(id(1)));
        assertEquals(Duration.ofSeconds(30), memory.ttl(id(1)));
        assertEquals(1, products.cardReads());
    }

    @Test
    void aHitDoesNotTouchTheDatabaseOrTheRenderer() {
        body(id(1));
        String again = service.body(id(1), c -> {
            throw new AssertionError("при попадании в кэш тело не строится");
        });
        assertEquals("{\"title\":\"Товар 1\"}", again);
        assertEquals(1, products.cardReads());
    }

    @Test
    void aProductThatIsNotPublishedIsNotFoundAndNothingIsCached() {
        ProblemException e = assertThrows(ProblemException.class, () -> body(id(7)));
        assertEquals(ProblemType.NOT_FOUND, e.type());
        assertFalse(memory.has(id(7)));
    }

    @Test
    void evictingMakesTheNextReadSeeTheDatabaseAgain() {
        body(id(1));
        products.withdraw(id(1));
        memory.cache().evictLater(id(1));
        assertFalse(memory.has(id(1)));
        assertEquals(ProblemType.NOT_FOUND, assertThrows(ProblemException.class, () -> body(id(1))).type());
    }

    @Test
    void anUnavailableRedisIsNotFatalTheCardComesFromTheDatabase() {
        memory.down(true);
        assertEquals("{\"title\":\"Товар 1\"}", body(id(1)));
        assertEquals("{\"title\":\"Товар 1\"}", body(id(1)));
        assertEquals(2, products.cardReads(), "кэш не отвечает, каждый запрос читает базу");
    }

    @Test
    void withoutRedisConfiguredTheCacheIsSwitchedOff() {
        ProductCache off = new ProductCache(null, CacheSettings.defaults(), new SimpleMeterRegistry());
        assertFalse(off.enabled());
        StorefrontProductService plain = new StorefrontProductService(products, off);
        assertEquals("{\"title\":\"Товар 1\"}", plain.body(id(1), c -> "{\"title\":\"" + c.title() + "\"}"));
        off.evictLater(id(1));
        off.close();
    }

    @Test
    void theTtlComesFromTheSettingsAndIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new CacheSettings(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CacheSettings(Duration.ofSeconds(3601)));
        assertEquals(Duration.ofSeconds(30), CacheSettings.defaults().productTtl());
    }
}
