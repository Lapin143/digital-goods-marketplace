package dgm.inventory.inventory.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.inventory.inventory.crypto.KeyCipher;
import dgm.inventory.inventory.crypto.KeyHasher;
import dgm.inventory.inventory.keys.repository.KeyRepository;
import dgm.inventory.inventory.keys.service.DataKeys;
import dgm.inventory.inventory.keys.service.SellerKeysService;
import dgm.inventory.inventory.registry.ProductCopy;
import dgm.inventory.inventory.reservation.domain.Reservation;
import dgm.inventory.inventory.reservation.repository.ReservationRepository;
import dgm.inventory.inventory.service.InventoryTransaction;
import dgm.kit.json.Json;
import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.secret.SecretValue;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Ключи продавца для заказа с выдачей по API ({@code storeSellerKeys}, OP-99, ADR-028, Р-190): шифрование как у обычных ключей, привязка к заказу и резерву,
 * статус {@code issued}; число ключей, дубликаты и формат; повтор тех же ключей и другие ключи; резерв не {@code used}; ничего не записано при отказе, значений
 * нет ни в ошибках, ни в базе. База и транзакция в памяти (с откатом), шифрование настоящее.
 */
@Tag("TC-964")
@Tag("TC-967")
@Tag("TC-969")
class SellerKeysServiceTest {

    private static final UUID SELLER = UUID.fromString("0199e0a0-0000-4000-8000-0000000000b1");
    private static final UUID PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-0000000002a2");
    private static final UUID POOL_PRODUCT = UUID.fromString("0199e0a0-0000-7000-8000-000000000201");
    private static final UUID ORDER = UUID.fromString("0199e0a0-0000-7000-8000-000000000301");
    private static final UUID RESERVATION = UUID.fromString("0199e0a0-0000-7000-8000-000000000c01");
    private static final UUID REQUEST = UUID.fromString("0199e0a0-0000-7000-8000-000000000a01");
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Строка таблицы ключей. */
    private static final class Row {
        final UUID id;
        final UUID productId;
        final String hmac;
        final byte[] nonce;
        final byte[] ciphertext;
        final int dekId;
        String status = "free";
        UUID orderId;
        UUID reservationId;

        Row(UUID id, UUID productId, String hmac, int dekId, byte[] nonce, byte[] ciphertext) {
            this.id = id;
            this.productId = productId;
            this.hmac = hmac;
            this.dekId = dekId;
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }

        Row copy() {
            Row row = new Row(id, productId, hmac, dekId, nonce, ciphertext);
            row.status = status;
            row.orderId = orderId;
            row.reservationId = reservationId;
            return row;
        }
    }

    /** Таблица ключей: уникальность {@code (product_id, hmac)} и переходы SM-03 как в базе. */
    private static final class Keys implements KeyRepository {
        Map<UUID, Row> rows = new LinkedHashMap<>();

        @Override
        public Map<String, Integer> countByStatus(UUID productId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> reserveFree(UUID productId, int quantity, UUID orderId, UUID reservationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int releaseByReservation(UUID reservationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int issueByReservation(UUID reservationId, Instant at) {
            int count = 0;
            for (Row row : rows.values()) {
                if (reservationId.equals(row.reservationId) && "reserved".equals(row.status)) {
                    row.status = "issued";
                    count++;
                }
            }
            return count;
        }

        @Override
        public List<StoredKey> byOrder(UUID orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> insertNew(UUID productId, List<NewKey> keys) {
            Set<String> inserted = new HashSet<>();
            for (NewKey key : keys) {
                String hmac = KeyHasher.hex(key.hmac());
                boolean exists = rows.values().stream().anyMatch(r -> r.productId.equals(productId) && r.hmac.equals(hmac));
                if (!exists) {
                    rows.put(key.id(), new Row(key.id(), productId, hmac, key.dekId(), key.nonce(), key.ciphertext()));
                    inserted.add(hmac);
                }
            }
            return inserted;
        }

        @Override
        public int reserveByIds(List<UUID> keyIds, UUID orderId, UUID reservationId) {
            int count = 0;
            for (UUID id : keyIds) {
                Row row = rows.get(id);
                if (row != null && "free".equals(row.status)) {
                    row.status = "reserved";
                    row.orderId = orderId;
                    row.reservationId = reservationId;
                    count++;
                }
            }
            return count;
        }

        @Override
        public Set<String> hmacsByReservation(UUID reservationId) {
            Set<String> hmacs = new HashSet<>();
            rows.values().stream().filter(r -> reservationId.equals(r.reservationId)).forEach(r -> hmacs.add(r.hmac));
            return hmacs;
        }
    }

    /** Резервы: один на заказ, строка блокируется чтением {@code lockLatestByOrder}. */
    private static final class Reservations implements ReservationRepository {
        Reservation reservation;
        int locks;

        @Override
        public Optional<Reservation> find(UUID id) {
            return Optional.ofNullable(reservation).filter(r -> r.id().equals(id));
        }

        @Override
        public Optional<Reservation> latestByOrder(UUID orderId) {
            return Optional.ofNullable(reservation).filter(r -> r.orderId().equals(orderId));
        }

        @Override
        public Optional<Reservation> lockLatestByOrder(UUID orderId) {
            locks++;
            return latestByOrder(orderId);
        }

        @Override
        public Optional<Reservation> activeByOrder(UUID orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(Reservation reservation, Instant createdAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> releaseIfDue(UUID id, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> release(UUID id, String reason, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> markUsed(UUID id, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Reservation> releaseUsed(UUID id, String reason, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> overdue(Instant dueBefore, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Reservation> activeAfter(UUID after, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    /** Транзакция с откатом: при исключении таблица ключей возвращается к состоянию до неё. */
    private static final class Transaction implements InventoryTransaction {
        private final Keys keys;

        Transaction(Keys keys) {
            this.keys = keys;
        }

        @Override
        public <T> T execute(Supplier<T> action) {
            Map<UUID, Row> before = new LinkedHashMap<>();
            keys.rows.forEach((id, row) -> before.put(id, row.copy()));
            try {
                return action.get();
            } catch (RuntimeException e) {
                keys.rows = before;
                throw e;
            }
        }

        @Override
        public void afterCommit(Runnable action) {
            action.run();
        }

        @Override
        public void publish(String topic, String aggregateType, String aggregateId, String eventType, int schemaVersion, Map<String, Object> data) {
            throw new UnsupportedOperationException("ключи продавца событий не пишут");
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final KeyCipher cipher = new KeyCipher(new byte[32], random);
    private final KeyHasher hasher = new KeyHasher("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
    private FakePool pool;
    private Keys keys;
    private Reservations reservations;
    private DataKeys dataKeys;
    private SellerKeysService service;

    @BeforeEach
    void setUp() {
        pool = new FakePool();
        keys = new Keys();
        reservations = new Reservations();
        pool.products.rows.put(PRODUCT, new ProductCopy(PRODUCT, SELLER, "seller_api", "published", 1));
        pool.products.rows.put(POOL_PRODUCT, new ProductCopy(POOL_PRODUCT, SELLER, "key_pool", "published", 1));
        reservations.reservation = reservation("used");
        dataKeys = new DataKeys(pool.dataKeys, cipher);
        service = new SellerKeysService(reservations, pool.products, keys, dataKeys, cipher, hasher, new Transaction(keys), CLOCK);
    }

    private static Reservation reservation(String status) {
        return new Reservation(RESERVATION, ORDER, PRODUCT, 2, status, NOW.plusSeconds(900), "released".equals(status) ? Reservation.ORDER_CANCELLED : null);
    }

    private ProblemException refused(List<String> values) {
        return assertThrows(ProblemException.class, () -> service.store(ORDER, REQUEST, values));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> errors(ProblemException e) {
        return (List<Map<String, Object>>) e.extensions().get("errors");
    }

    private String open(Row row) {
        SecretValue value = cipher.open(dataKeys.byId(row.dekId), row.id, row.productId, row.nonce, row.ciphertext);
        try {
            return value.reveal();
        } finally {
            value.destroy();
        }
    }

    @Test
    void theKeysAreEncryptedLikeOrdinaryKeysBoundToTheOrderAndTheReservationAndIssued() {
        SellerKeysService.Stored stored = service.store(ORDER, REQUEST, List.of("SELLR-KEY00-00001", "SELLR-KEY00-00002"));

        assertEquals(new SellerKeysService.Stored(ORDER, 2), stored);
        assertEquals(2, keys.rows.size());
        List<String> plain = new ArrayList<>();
        for (Row row : keys.rows.values()) {
            assertEquals("issued", row.status, "free, reserved, issued одной транзакцией");
            assertEquals(ORDER, row.orderId);
            assertEquals(RESERVATION, row.reservationId);
            assertEquals(PRODUCT, row.productId);
            assertEquals(12, row.nonce.length);
            assertFalse(new String(row.ciphertext, StandardCharsets.ISO_8859_1).contains("SELLR-KEY"), "открытого значения в базе нет");
            plain.add(open(row));
        }
        assertEquals(List.of("SELLR-KEY00-00001", "SELLR-KEY00-00002"), plain, "расшифровка возвращает присланные значения");
        assertEquals(1, reservations.locks, "резерв заказа заблокирован на время записи");
    }

    @Test
    void theValueIsStrippedLikeAnUploadedKey() {
        service.store(ORDER, REQUEST, List.of("  AAA-111 \n", "BBB-222"));

        assertEquals(List.of("AAA-111", "BBB-222"), keys.rows.values().stream().map(this::open).toList());
    }

    @Test
    void theSameKeysAgainGiveTheSameResultAndAddNothing() {
        service.store(ORDER, REQUEST, List.of("KEY-1", "KEY-2"));

        SellerKeysService.Stored again = service.store(ORDER, REQUEST, List.of("KEY-2", "KEY-1"));

        assertEquals(new SellerKeysService.Stored(ORDER, 2), again, "тот же итог без второго ключа");
        assertEquals(2, keys.rows.size());
    }

    @Test
    void otherKeysForTheOrderThatAlreadyHasKeysAreRefusedAndTheStoredOnesStay() {
        service.store(ORDER, REQUEST, List.of("KEY-1", "KEY-2"));

        ProblemException e = refused(List.of("KEY-1", "KEY-3"));

        assertEquals(ProblemType.STATE_CONFLICT, e.type());
        assertEquals(2, keys.rows.size());
        assertEquals(List.of("KEY-1", "KEY-2"), keys.rows.values().stream().map(this::open).toList());
    }

    @Test
    void theNumberOfKeysMustEqualTheQuantityOfTheOrder() {
        ProblemException few = refused(List.of("KEY-1"));
        ProblemException many = refused(List.of("KEY-1", "KEY-2", "KEY-3"));

        for (ProblemException e : List.of(few, many)) {
            assertEquals(ProblemType.VALIDATION_FAILED, e.type());
            assertEquals("/keys", errors(e).get(0).get("pointer"));
            assertEquals("out_of_range", errors(e).get(0).get("code"));
        }
        assertTrue(keys.rows.isEmpty(), "ничего не записано");
    }

    @Test
    void aDuplicateInsideTheAnswerIsRefusedWithThePositionOnly() {
        ProblemException e = refused(List.of("SAME-KEY", "SAME-KEY"));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals("/keys/1", errors(e).get(0).get("pointer"));
        assertEquals("not_unique", errors(e).get(0).get("code"));
        assertFalse(Json.write(e.extensions()).contains("SAME-KEY"), "значения в ошибке нет");
        assertTrue(keys.rows.isEmpty());
    }

    @Test
    void aValueThatTheProductAlreadyHasIsRefusedAndNothingOfTheAnswerIsRecorded() {
        // значение уже выдано по другому заказу того же товара (INV-03)
        service.store(ORDER, REQUEST, List.of("OLD-KEY", "OLD-KEY-2"));
        UUID otherOrder = UUID.fromString("0199e0a0-0000-7000-8000-000000000302");
        reservations.reservation = new Reservation(UUID.fromString("0199e0a0-0000-7000-8000-000000000c02"), otherOrder, PRODUCT, 2, "used", NOW.plusSeconds(900),
                null);

        ProblemException e = assertThrows(ProblemException.class, () -> service.store(otherOrder, REQUEST, List.of("NEW-KEY", "OLD-KEY")));

        assertEquals(ProblemType.VALIDATION_FAILED, e.type());
        assertEquals(1, errors(e).size());
        assertEquals("/keys/1", errors(e).get(0).get("pointer"));
        assertEquals("not_unique", errors(e).get(0).get("code"));
        assertFalse(Json.write(e.extensions()).contains("OLD-KEY"));
        assertEquals(2, keys.rows.size(), "новое значение откатилось вместе с отказом, остались только ключи первого заказа");
        assertTrue(keys.rows.values().stream().allMatch(r -> ORDER.equals(r.orderId)));
    }

    @Test
    void anEmptyAndATooLongValueAreRefused() {
        ProblemException empty = refused(List.of("KEY-1", "   "));
        ProblemException longOne = refused(List.of("KEY-1", "x".repeat(1025)));

        assertEquals("invalid_format", errors(empty).get(0).get("code"));
        assertEquals("/keys/1", errors(empty).get(0).get("pointer"));
        assertEquals("too_long", errors(longOne).get(0).get("code"));
        assertTrue(keys.rows.isEmpty());
    }

    @Test
    void aReservationThatIsNotUsedGives409WhetherItWaitsForPaymentOrWasReleased() {
        for (String status : List.of("active", "released")) {
            reservations.reservation = reservation(status);

            ProblemException e = refused(List.of("KEY-1", "KEY-2"));

            assertEquals(ProblemType.STATE_CONFLICT, e.type(), status);
            assertEquals(status, e.extensions().get("currentStatus"));
        }
        assertTrue(keys.rows.isEmpty(), "ключи не приняты: после отмены заказа им делать нечего");
    }

    @Test
    void anOrderWithoutAReservationOrWithAProductOfThePoolIsNotFound() {
        reservations.reservation = null;
        assertEquals(ProblemType.NOT_FOUND, refused(List.of("KEY-1", "KEY-2")).type());

        reservations.reservation = new Reservation(RESERVATION, ORDER, POOL_PRODUCT, 2, "used", NOW.plusSeconds(900), null);
        assertEquals(ProblemType.NOT_FOUND, refused(List.of("KEY-1", "KEY-2")).type(), "у товара с пулом ключей продавец ключи не присылает");
    }
}
