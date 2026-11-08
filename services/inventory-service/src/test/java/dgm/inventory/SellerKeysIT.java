package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Ключи продавца в хранилище ключей на стенде ({@code storeSellerKeys}, OP-99, ADR-028): настоящий сервис, база с триггерами статусов ключа и
 * резерва, вызовы от имени {@code delivery-service} по mTLS. Ключи, присланные продавцом в ответ на запрос выдачи, шифруются, получают HMAC, привязываются
 * к заказу и сразу выданы одной транзакцией; значения читает только {@code getKeyValues}. Повтор тех же ключей даёт тот же итог, другие ключи это 409,
 * число ключей, дубликат и лишнее поле это 422, состояние резерва проверяется (оплаченный резерв товара по API, иначе 409 и 404).
 */
class SellerKeysIT extends ReservationStandIT {

    private static final String SELLER_KEYS = "/internal/v1/seller-keys";

    /** Товар по API с остатком. */
    private record Api(Seller seller, UUID productId) {
    }

    private static Api apiProduct(int stock) throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product("published", "seller_api");
        Response set = gateway.request("PUT", PRODUCTS + "/" + productId + "/api-stock", "application/json", ("{\"quantity\":" + stock + "}").getBytes(StandardCharsets.UTF_8),
                with(seller.auth, "If-Match", "\"1\""));
        assertEquals(200, set.status(), set.body());
        return new Api(seller, productId);
    }

    /** Оплаченный заказ: резерв по количеству подтверждён (статус {@code used}, ключей у заказа ещё нет). */
    private static UUID paidOrder(UUID productId, int quantity) throws IOException {
        UUID orderId = UUID.randomUUID();
        assertEquals(201, reserve(orderId, productId, quantity).status());
        assertEquals(200, confirm(orderId, productId, quantity).status());
        return orderId;
    }

    private static List<String> sellerKeys(int count) {
        String prefix = "SELLER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            keys.add(prefix + "-" + i);
        }
        return keys;
    }

    private static String body(UUID orderId, UUID requestId, Object keys) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId.toString());
        body.put("requestId", requestId.toString());
        body.put("keys", keys);
        return Json.write(body);
    }

    private static Response store(StandHttp caller, String json, String idempotencyKey) throws IOException {
        return caller.post(SELLER_KEYS, json, "Idempotency-Key", idempotencyKey);
    }

    private static Response store(UUID orderId, List<String> keys) throws IOException {
        return store(delivery, body(orderId, UUID.randomUUID(), keys), key());
    }

    private static int keysOfProduct(UUID productId) {
        Integer n = db.queryForObject("select count(*) from inventory.key where product_id = ?", Integer.class, productId);
        return n == null ? 0 : n;
    }

    /** Значения, которые отдаёт {@code getKeyValues} заказу. */
    @SuppressWarnings("unchecked")
    private static List<String> valuesOf(UUID orderId) throws IOException {
        Response response = keyValues(delivery, orderId);
        assertEquals(200, response.status(), response.body());
        List<String> values = new ArrayList<>();
        for (Object item : (List<Object>) response.json().get("keys")) {
            values.add((String) ((Map<String, Object>) item).get("value"));
        }
        return values;
    }

    // ------------------------------------------------------------------------------------------------ TC-964

    @Tag("TC-964")
    @Test
    void theKeysOfTheSellerAreEncryptedIssuedToTheOrderInOneStepAndReadBackByTheDeliveryService() throws Exception {
        Api api = apiProduct(5);
        UUID orderId = paidOrder(api.productId(), 2);
        assertEquals(0, keysOfProduct(api.productId()), "у заказа по API до ответа продавца ключей нет");
        List<String> keys = sellerKeys(2);
        UUID requestId = UUID.randomUUID();

        Response response = store(delivery, body(orderId, requestId, keys), key());

        assertEquals(200, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals(orderId.toString(), response.json().get("orderId"));
        assertEquals(2, ((Number) response.json().get("stored")).intValue());
        assertFalse(response.body().contains(keys.get(0)), "значений ключей в ответе нет");
        assertEquals(2, keysOfOrder(orderId, "issued"), "ключи сразу выданы заказу: free > reserved > issued одной транзакцией");
        assertEquals(2, keysOfProduct(api.productId()));
        assertEquals("used", status(orderId), "резерв остаётся «использован»");
        for (String value : keys) {
            Long plain = db.queryForObject("select count(*) from inventory.key t where t::text like ?", Long.class, "%" + value + "%");
            assertEquals(0L, plain.longValue(), "значение ключа лежит в базе открытым текстом (INV-20): " + value);
        }
        Integer distinct = db.queryForObject("select count(distinct hmac) from inventory.key where order_id = ?", Integer.class, orderId);
        assertEquals(2, distinct.intValue(), "у каждого ключа свой HMAC");
        assertEquals(keys.stream().sorted().toList(), valuesOf(orderId).stream().sorted().toList(), "getKeyValues расшифровывает то, что прислал продавец");
        assertFalse(valuesOf(orderId).isEmpty());
    }

    @Tag("TC-964")
    @Test
    void aSingleKeyOrderAndAFullTenKeyOrderAreStoredAndTheStockOfTheProductIsNotTouched() throws Exception {
        Api api = apiProduct(12);
        UUID one = paidOrder(api.productId(), 1);
        UUID ten = paidOrder(api.productId(), 10);
        Integer before = db.queryForObject("select quantity from inventory.api_stock where product_id = ?", Integer.class, api.productId());

        assertEquals(200, store(one, sellerKeys(1)).status());
        assertEquals(200, store(ten, sellerKeys(10)).status());

        assertEquals(1, keysOfOrder(one, "issued"));
        assertEquals(10, keysOfOrder(ten, "issued"));
        assertEquals(before, db.queryForObject("select quantity from inventory.api_stock where product_id = ?", Integer.class, api.productId()),
                "остаток считается по количеству, ключи продавца его не меняют");
    }

    // ------------------------------------------------------------------------------------------------ TC-967

    @Tag("TC-967")
    @Test
    void theSameKeysAgainGiveTheSameResultAndOtherKeysAreRefusedWithoutChangingTheStoredOnes() throws Exception {
        Api api = apiProduct(3);
        UUID orderId = paidOrder(api.productId(), 2);
        List<String> keys = sellerKeys(2);
        String first = body(orderId, UUID.randomUUID(), keys);
        String idempotencyKey = key();
        Response stored = store(delivery, first, idempotencyKey);
        assertEquals(200, stored.status(), stored.body());

        Response same = store(delivery, first, idempotencyKey);
        assertEquals(200, same.status(), same.body());
        assertEquals(stored.json(), same.json(), "тот же вызов под тем же ключом идемпотентности: то же содержимое ответа (хранилище идемпотентности может переставить поля)");
        Response again = store(orderId, keys);
        assertEquals(200, again.status(), again.body());
        assertEquals(2, ((Number) again.json().get("stored")).intValue(), "те же ключи под новым ключом идемпотентности: тот же итог без вторых ключей");
        Response reordered = store(orderId, List.of(keys.get(1), keys.get(0)));
        assertEquals(200, reordered.status(), "набор тот же, порядок не важен: " + reordered.body());

        Response other = store(orderId, sellerKeys(2));
        assertEquals(409, other.status(), other.body());
        assertEquals("state-conflict", code(other));
        assertEquals(2, keysOfOrder(orderId, "issued"), "ключей по-прежнему два");
        assertEquals(2, keysOfProduct(api.productId()));
        assertEquals(keys.stream().sorted().toList(), valuesOf(orderId).stream().sorted().toList(), "значения не изменились");
    }

    // ------------------------------------------------------------------------------------------------ TC-969

    @Tag("TC-969")
    @Test
    void aWrongNumberOfKeysAndADuplicateInTheCallAreRefusedWithTheirPositionAndNothingIsStored() throws Exception {
        Api api = apiProduct(3);
        UUID orderId = paidOrder(api.productId(), 2);

        Response few = store(orderId, sellerKeys(1));
        Response many = store(orderId, sellerKeys(3));
        String twice = "DUP-" + UUID.randomUUID().toString().substring(0, 8);
        Response duplicate = store(orderId, List.of(twice, twice));
        Response tooLong = store(orderId, List.of("x".repeat(1025), "y"));

        for (Response refused : List.of(few, many)) {
            assertEquals(422, refused.status(), refused.body());
            assertEquals("validation-failed", code(refused));
            assertTrue(refused.body().contains("/keys") && refused.body().contains("out_of_range"), refused.body());
        }
        assertEquals(422, duplicate.status(), duplicate.body());
        assertTrue(duplicate.body().contains("/keys/1") && duplicate.body().contains("not_unique"), duplicate.body());
        assertFalse(duplicate.body().contains(twice), "значение ключа в ошибке не повторяется");
        assertEquals(422, tooLong.status(), tooLong.body());
        assertTrue(tooLong.body().contains("/keys/0") && tooLong.body().contains("too_long"), tooLong.body());
        assertEquals(0, keysOfProduct(api.productId()), "ничего не сохранено: ни один ключ не лёг в пул");
        assertEquals("used", status(orderId));

        assertEquals(200, store(orderId, sellerKeys(2)).status(), "после исправления ответ принимается");
    }

    @Tag("TC-969")
    @Test
    void aKeyAlreadyInThePoolOfTheProductIsRefusedAsNotUniqueAndTheOrderKeepsNoKeys() throws Exception {
        Api api = apiProduct(3);
        UUID first = paidOrder(api.productId(), 1);
        UUID second = paidOrder(api.productId(), 1);
        List<String> taken = sellerKeys(1);
        assertEquals(200, store(first, taken).status());

        Response duplicate = store(second, taken);

        assertEquals(422, duplicate.status(), duplicate.body());
        assertTrue(duplicate.body().contains("/keys/0") && duplicate.body().contains("not_unique"), duplicate.body());
        assertFalse(duplicate.body().contains(taken.get(0)), "значение ключа в ошибке не повторяется");
        assertEquals(0, keysOfOrder(second, "issued") + keysOfOrder(second, "reserved") + keysOfOrder(second, "free"), "у второго заказа ключей нет");
        assertEquals(1, keysOfProduct(api.productId()), "в пуле остался один ключ первого заказа: вставка второго откатилась целиком");
        assertEquals(200, store(second, sellerKeys(1)).status());
    }

    @Tag("TC-969")
    @Test
    void aReservationThatIsNotPaidAnUnknownOrderAndAKeyPoolProductAreRefusedAndNothingIsStored() throws Exception {
        Api api = apiProduct(3);
        UUID unpaid = UUID.randomUUID();
        assertEquals(201, reserve(unpaid, api.productId(), 1).status());
        Response early = store(unpaid, sellerKeys(1));
        assertEquals(409, early.status(), "резерв не оплачен: " + early.body());
        assertEquals("state-conflict", code(early));
        assertEquals(0, keysOfProduct(api.productId()));

        Response unknown = store(UUID.randomUUID(), sellerKeys(1));
        assertEquals(404, unknown.status(), unknown.body());
        assertEquals("not-found", code(unknown));

        Pool pool = pool(2);
        UUID byKeys = UUID.randomUUID();
        assertEquals(201, reserve(byKeys, pool.productId(), 1).status());
        assertEquals(200, confirm(byKeys, pool.productId(), 1).status());
        Response notApi = store(byKeys, sellerKeys(1));
        assertEquals(404, notApi.status(), "товар с пулом ключей ключей продавца не принимает: " + notApi.body());
        assertEquals(1, keysOfOrder(byKeys, "issued"), "выданный ключ пула не тронут");
    }

    @Tag("TC-969")
    @Test
    void aBodyWithExtraFieldsOrBadIdentifiersIsRefusedBeforeAnythingIsStored() throws Exception {
        Api api = apiProduct(3);
        UUID orderId = paidOrder(api.productId(), 1);
        String key = sellerKeys(1).get(0);
        UUID request = UUID.randomUUID();
        List<String> bodies = List.of("{\"orderId\": \"" + orderId + "\", \"requestId\": \"" + request + "\", \"keys\": [\"" + key + "\"], \"note\": \"x\"}",
                "{\"orderId\": \"" + orderId + "\", \"keys\": [\"" + key + "\"]}", "{\"orderId\": \"не-идентификатор\", \"requestId\": \"" + request + "\", \"keys\": [\""
                        + key + "\"]}", "{\"orderId\": \"" + orderId + "\", \"requestId\": \"" + request + "\", \"keys\": []}",
                "{\"orderId\": \"" + orderId + "\", \"requestId\": \"" + request + "\", \"keys\": [5]}");

        for (String json : bodies) {
            Response response = store(delivery, json, key());

            assertEquals(422, response.status(), json + " -> " + response.body());
            assertEquals("validation-failed", code(response));
            assertFalse(response.body().contains(key), "значение ключа в ошибке не повторяется");
        }
        assertEquals(400, store(delivery, "это не JSON", key()).status());
        assertEquals(0, keysOfProduct(api.productId()));
    }

    @Tag("TC-969")
    @Test
    void onlyTheDeliveryServiceMayStoreSellerKeysAndTheKeyIdempotencyIsRequired() throws Exception {
        Api api = apiProduct(3);
        UUID orderId = paidOrder(api.productId(), 1);
        String json = body(orderId, UUID.randomUUID(), sellerKeys(1));

        for (String caller : List.of("order-service", "payment-service", "api-gateway", "platform-service", "catalog-service")) {
            Response refused = store(StandHttp.asService(caller, PORT), json, key());
            assertEquals(403, refused.status(), "storeSellerKeys от " + caller + ": " + refused.body());
        }
        Response withoutKey = delivery.post(SELLER_KEYS, json);
        assertNotNull(withoutKey);
        assertTrue(withoutKey.status() == 400 || withoutKey.status() == 422, "без Idempotency-Key вызов не принят: " + withoutKey.status());
        assertEquals(0, keysOfProduct(api.productId()), "ничего не сохранено");
        assertEquals(200, store(delivery, json, key()).status(), "допущенный вызывающий с ключом идемпотентности проходит");
    }
}
