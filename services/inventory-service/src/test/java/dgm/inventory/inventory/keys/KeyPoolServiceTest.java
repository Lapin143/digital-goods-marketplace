package dgm.inventory.inventory.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.keys.domain.KeyBatch;
import dgm.inventory.inventory.keys.domain.RejectReason;
import dgm.inventory.inventory.keys.domain.RejectedLine;
import dgm.inventory.inventory.keys.domain.StockInfo;
import dgm.inventory.inventory.keys.domain.UploadReport;
import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.keys.service.DataKeys;
import dgm.inventory.inventory.keys.service.KeyPoolService;
import dgm.inventory.inventory.registry.ProductCopy;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Пул ключей: права, статусы, дубли, предел, шифрование и событие остатка (US-4.2, US-4.3, US-4.5, ADR-009). */
class KeyPoolServiceTest {

    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00.123Z"), ZoneOffset.UTC);

    private final SecureRandom random = new SecureRandom();
    private final KeyCipher cipher = new KeyCipher(new byte[32], random);
    private final KeyHasher hasher = new KeyHasher("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
    private FakePool pool;
    private KeyPoolService service;

    @BeforeEach
    void setUp() {
        pool = new FakePool();
        pool.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", "draft", 1));
        service = new KeyPoolService(pool.products, pool.keys, new DataKeys(pool.dataKeys, cipher), cipher, hasher, pool.transaction, CLOCK);
    }

    private static ProblemException refused(Runnable call) {
        return assertThrows(ProblemException.class, call::run);
    }

    @Tag("TC-401")
    @Test
    void anUploadStoresEncryptedKeysAndReportsTheStock() {
        UploadReport report = service.upload(SELLER, PRODUCT, List.of("A-1", "A-2", "A-3"));
        assertEquals(new UploadReport(3, 0, List.of(), 3, 100_000), report);
        assertEquals(3, pool.keys.stored(PRODUCT));
        for (KeyRepository.NewKey row : pool.keys.byProduct.get(PRODUCT).values()) {
            assertEquals(1, row.dekId());
            assertEquals(12, row.nonce().length);
            String stored = new String(row.ciphertext(), StandardCharsets.ISO_8859_1);
            for (String plain : List.of("A-1", "A-2", "A-3")) {
                assertFalse(stored.contains(plain), "значения в базе нет");
            }
            SecretValue back = cipher.open(cipher.unwrap(pool.dataKeys.rows.get(0).wrapped()), row.id(), PRODUCT, row.nonce(), row.ciphertext());
            assertTrue(back.reveal().startsWith("A-"), "значение расшифровывается активным DEK по AAD из id ключа и товара");
        }
    }

    @Tag("TC-410")
    @Test
    void theFirstUploadCreatesTheDataKeyUnderTheAdvisoryLockAndLaterUploadsReuseIt() {
        service.upload(SELLER, PRODUCT, List.of("A-1"));
        service.upload(SELLER, PRODUCT, List.of("A-2"));
        assertEquals(1, pool.dataKeys.rows.size());
        assertEquals(1, pool.dataKeys.locks, "блокировка нужна только при создании первого DEK");
        assertEquals(60, pool.dataKeys.rows.get(0).wrapped().length);
    }

    @Tag("TC-401")
    @Test
    void emptyLongAndRepeatedLinesAreRejectedWithNumbersAndNoValues() {
        UploadReport report = service.upload(SELLER, PRODUCT, List.of("A-1", "A-2", "A-2", "", "x".repeat(1025), "A-3"));
        assertEquals(3, report.accepted());
        assertEquals(3, report.rejectedTotal());
        assertEquals(List.of(new RejectedLine(3, RejectReason.DUPLICATE_IN_UPLOAD), new RejectedLine(4, RejectReason.EMPTY),
                new RejectedLine(5, RejectReason.TOO_LONG)), report.rejected());
        assertEquals(3, report.available());
    }

    @Tag("TC-401")
    @Test
    void aValueAlreadyInThePoolIsRejectedAndTheOtherLinesAreAccepted() {
        service.upload(SELLER, PRODUCT, List.of("A-1", "A-2"));
        UploadReport again = service.upload(SELLER, PRODUCT, List.of("A-2", "A-4", "A-1", "A-5"));
        assertEquals(2, again.accepted());
        assertEquals(List.of(new RejectedLine(1, RejectReason.DUPLICATE_IN_POOL), new RejectedLine(3, RejectReason.DUPLICATE_IN_POOL)), again.rejected());
        assertEquals(4, again.available());
        assertEquals(4, pool.keys.stored(PRODUCT));
    }

    @Tag("TC-401")
    @Test
    void theSameValueInAnotherProductIsNotADuplicate() {
        UUID other = UUID.fromString("0199e0a0-0000-7000-8000-000000000202");
        pool.products.rows.put(other, new ProductCopy(other, SELLER, "key_pool", "published", 1));
        service.upload(SELLER, PRODUCT, List.of("A-1"));
        assertEquals(1, service.upload(SELLER, other, List.of("A-1")).accepted());
    }

    @Tag("TC-404")
    @Test
    void theProductRowIsLockedBeforeTheChecksSoParallelUploadsGoOneByOne() {
        service.upload(SELLER, PRODUCT, List.of("A-1"));
        assertEquals(List.of(PRODUCT), pool.products.locked);
    }

    @Tag("TC-406")
    @Test
    void stockChangedIsWrittenInTheSameTransactionWithTheNewStock() {
        service.upload(SELLER, PRODUCT, List.of("A-1", "A-2"));
        assertEquals(1, pool.transaction.events.size());
        FakePool.Transaction.Event event = pool.transaction.events.get(0);
        assertEquals("inventory.events", event.topic());
        assertEquals("stock.changed", event.type());
        assertEquals(PRODUCT.toString(), event.aggregateId());
        assertEquals(PRODUCT.toString(), event.data().get("productId"));
        assertEquals(2, event.data().get("available"));
        assertEquals(CLOCK.millis(), event.data().get("version"));
        assertEquals(Map.of("productId", PRODUCT.toString(), "available", 2, "version", CLOCK.millis()), event.data());
    }

    @Tag("TC-406")
    @Test
    void anUploadThatAddsNothingWritesNoEvent() {
        service.upload(SELLER, PRODUCT, List.of("A-1"));
        pool.transaction.events.clear();
        UploadReport again = service.upload(SELLER, PRODUCT, List.of("A-1", ""));
        assertEquals(0, again.accepted());
        assertEquals(List.of(), pool.transaction.events);
        assertEquals(1, again.available());
    }

    @Tag("TC-408")
    @Test
    void anotherSellersProductAndAnUnknownProductAreNotFound() {
        UUID stranger = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b2");
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.upload(stranger, PRODUCT, List.of("A-1"))).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.upload(SELLER, UUID.randomUUID(), List.of("A-1"))).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> service.stock(stranger, PRODUCT)).type());
        assertEquals(0, pool.keys.stored(PRODUCT));
    }

    @Tag("TC-407")
    @Test
    void onlyKeyPoolProductsInAnAllowedStatusAcceptKeys() {
        for (String status : List.of("draft", "on_moderation", "published", "rejected")) {
            pool.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", status, 1));
            assertEquals(1, service.upload(SELLER, PRODUCT, List.of("K-" + status)).accepted(), status);
        }
        for (String status : List.of("blocked", "archived")) {
            pool.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "key_pool", status, 1));
            ProblemException e = refused(() -> service.upload(SELLER, PRODUCT, List.of("Z-1")));
            assertEquals(ProblemType.STATE_CONFLICT, e.type(), status);
            assertEquals(status, e.extensions().get("currentStatus"));
        }
        pool.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "seller_api", "published", 1));
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> service.upload(SELLER, PRODUCT, List.of("Z-1"))).type());
        assertEquals(4, pool.keys.stored(PRODUCT));
    }

    @Tag("TC-405")
    @Test
    void theWholeListIsRefusedWhenThePoolWouldGrowPastTheLimit() {
        pool.keys.extra.put(PRODUCT, Map.of("free", 99_000, "reserved", 500, "issued", 495));
        assertEquals(5, service.upload(SELLER, PRODUCT, List.of("A-1", "A-2", "A-3", "A-4", "A-5")).accepted(), "ровно до предела: 99995 + 5");
        ProblemException e = refused(() -> service.upload(SELLER, PRODUCT, List.of("A-6")));
        assertEquals(ProblemType.POOL_LIMIT_EXCEEDED, e.type());
        assertEquals(409, e.type().status());
        assertEquals(5, pool.keys.stored(PRODUCT), "загрузка отклонена целиком, частично не принята");
    }

    @Tag("TC-405")
    @Test
    void rejectedLinesDoNotCountAgainstThePoolLimit() {
        pool.keys.extra.put(PRODUCT, Map.of("free", 99_999));
        UploadReport report = service.upload(SELLER, PRODUCT, List.of("A-1", "", "A-1"));
        assertEquals(1, report.accepted());
        assertEquals(2, report.rejectedTotal());
    }

    @Tag("TC-401")
    @Test
    void theReportShowsAtMostAThousandRejectedLinesButCountsAll() {
        List<String> lines = new ArrayList<>(List.of("A-1"));
        for (int i = 0; i < 1500; i++) {
            lines.add("");
        }
        UploadReport report = service.upload(SELLER, PRODUCT, lines);
        assertEquals(1500, report.rejectedTotal());
        assertEquals(1000, report.rejected().size());
        assertEquals(2, report.rejected().get(0).line());
    }

    @Tag("TC-406")
    @Test
    void stockCountsKeysByStatus() {
        pool.keys.extra.put(PRODUCT, Map.of("reserved", 3, "issued", 10));
        service.upload(SELLER, PRODUCT, List.of("A-1", "A-2"));
        StockInfo stock = service.stock(SELLER, PRODUCT);
        assertEquals(2, stock.available());
        assertEquals(3, stock.reserved());
        assertEquals(10, stock.issued());
        assertEquals(15, stock.poolSize());
        assertEquals(KeyBatch.POOL_LIMIT, stock.poolLimit());
        assertEquals(CLOCK.instant(), stock.updatedAt());
    }
}
