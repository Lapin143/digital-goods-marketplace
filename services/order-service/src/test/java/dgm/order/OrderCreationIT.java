package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.order.Neighbours.Call;
import dgm.order.Neighbours.Mode;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Покупка на стенде: {@code createOrder} от токена покупателя через mTLS до сервиса, настоящие PostgreSQL и Outbox, соседи саги это
 * {@link Neighbours}. Доказательство везде это строка в базе, запись в Outbox и записанные вызовы соседей, а не только ответ.
 */
class OrderCreationIT extends OrderStandIT {

    private static final long PRICE = 149_900;

    private final UUID seller = UUID.randomUUID();

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static Map<?, ?> map(Object value) {
        return (Map<?, ?>) value;
    }

    private static String text(Object value) {
        return (String) value;
    }

    private UUID product(int keys) {
        return NEIGHBOURS.product(seller, "Игра " + UUID.randomUUID().toString().substring(0, 8), PRICE, keys);
    }

    // ------------------------------------------------------------------------------------------------ TC-511

    @Tag("TC-511")
    @Test
    void anOrderReservesTheKeysOpensTheSessionAndWaitsForPayment() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(10);

        Response response = create(buyerToken(buyer, "ivan@mail.example"), product, 2);

        assertEquals(201, response.status(), response.body());
        Map<String, Object> order = response.json();
        String orderId = text(order.get("orderId"));
        assertEquals("/api/v1/orders/" + orderId, response.header("Location"));
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals("awaiting_payment", order.get("status"));
        assertEquals(2, number(order.get("quantity")));
        assertEquals(PRICE, number(map(order.get("unitPrice")).get("amount")));
        assertEquals(2 * PRICE, number(map(order.get("total")).get("amount")));
        assertEquals("RUB", map(order.get("total")).get("currency"));
        assertTrue(number(order.get("orderNumber")) >= 1001);
        assertEquals("ivan@mail.example", map(order.get("delivery")).get("address"));
        assertEquals(false, map(order.get("delivery")).get("masked"));
        assertNull(order.get("cancelReason"));
        assertFalse(order.containsKey("commission"), "комиссия платформы покупателю не показывается");
        assertFalse(order.containsKey("sellerId"));

        Instant reserveEnd = Instant.parse(text(order.get("reserveExpiresAt")));
        Instant sessionEnd = Instant.parse(text(map(order.get("paymentSession")).get("expiresAt")));
        assertEquals(order.get("paymentSessionExpiresAt"), map(order.get("paymentSession")).get("expiresAt"));
        assertTrue(sessionEnd.isBefore(reserveEnd), "платёжная сессия короче резерва (INV-07)");
        assertTrue(text(map(order.get("paymentSession")).get("url")).startsWith("https://"));

        Map<String, Object> row = order(orderId);
        assertNotNull(row);
        assertEquals("awaiting_payment", row.get("status"));
        assertEquals(buyer, row.get("buyer_id"));
        assertEquals(seller, row.get("seller_id"), "продавец из карточки каталога, а не из запроса");
        assertEquals(product, row.get("product_id"));
        assertEquals(PRICE, number(row.get("unit_price")), "цена из каталога на момент заказа (снимок)");
        assertEquals(2 * PRICE, number(row.get("amount")));
        assertEquals(200, number(row.get("commission_rate_bp")));
        assertEquals(5_996, number(row.get("commission")), "2 % от 2 998,00 ₽ = 59,96 ₽ (INV-13)");
        assertEquals("ivan@mail.example", row.get("delivery_address"));
        assertEquals(text(map(order.get("paymentSession")).get("url")), row.get("payment_session_url"));

        assertEquals(8, NEIGHBOURS.keysLeft(product), "два ключа в резерве");
    }

    @Tag("TC-511")
    @Test
    void theSagaCallsTheNeighboursOnceEachInTheOrderOfTheSequence() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);

        Response response = create(buyerToken(buyer), product, 1);

        assertEquals(201, response.status(), response.body());
        String orderId = text(response.json().get("orderId"));
        List<Call> calls = NEIGHBOURS.calls();
        assertEquals(List.of("catalog", "inventory", "payment"), calls.stream().map(Call::neighbour).toList());
        assertEquals("GET", calls.get(0).method());
        assertEquals("/internal/v1/products/" + product, calls.get(0).path());

        Call reserve = calls.get(1);
        assertEquals("POST", reserve.method());
        Map<String, Object> reserveBody = Json.parseObject(reserve.body());
        assertEquals(orderId, reserveBody.get("orderId"));
        assertEquals(product.toString(), reserveBody.get("productId"));
        assertEquals(1, number(reserveBody.get("quantity")));
        assertNotNull(reserve.headers().get("idempotency-key"), "резерв идемпотентен: ключ выведен из шага и заказа");

        Call session = calls.get(2);
        Map<String, Object> sessionBody = Json.parseObject(session.body());
        assertEquals(orderId, sessionBody.get("orderId"));
        assertEquals(PRICE, number(map(sessionBody.get("amount")).get("amount")));
        assertNotNull(session.headers().get("idempotency-key"));
    }

    @Tag("TC-511")
    @Test
    void orderCreatedIsPublishedOnceInTheSameTransactionAsTheStatusAndNothingElse() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);

        Response response = create(buyerToken(buyer), product, 3);

        assertEquals(201, response.status(), response.body());
        Map<String, Object> order = response.json();
        String orderId = text(order.get("orderId"));
        List<Map<String, Object>> created = events(orderId, "order.created");
        assertEquals(1, created.size());
        assertEquals("order.events", created.get(0).get("topic"));
        assertEquals("Order", created.get(0).get("aggregate_type"));
        Map<String, Object> data = data(created.get(0));
        assertEquals(orderId, data.get("orderId"));
        assertEquals(buyer.toString(), data.get("buyerId"));
        assertEquals(seller.toString(), data.get("sellerId"));
        assertEquals(product.toString(), data.get("productId"));
        assertEquals(3, number(data.get("quantity")));
        assertEquals(PRICE, number(map(data.get("unitPrice")).get("amount")));
        assertEquals(3 * PRICE, number(map(data.get("total")).get("amount")));
        assertEquals(8_994, number(map(data.get("commission")).get("amount")), "2 % от 4 497,00 ₽ = 89,94 ₽");
        assertEquals(order.get("reserveExpiresAt"), data.get("reserveExpiresAt"));
        assertEquals(order.get("paymentSessionExpiresAt"), data.get("paymentSessionExpiresAt"));
        assertEquals(1, eventCount(orderId), "других событий у заказа нет");
    }

    @Tag("TC-511")
    @Test
    void theSameKeyGivesTheSameOrderWithoutNewCallsAndAnotherBodyIsRefused() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);
        String token = buyerToken(buyer);
        String key = key();

        Response first = create(token, product, 2, key);
        assertEquals(201, first.status(), first.body());
        Response replay = create(token, product, 2, key);

        assertEquals(201, replay.status(), replay.body());
        assertEquals("true", replay.header("Idempotency-Replayed"));
        assertEquals(first.json().get("orderId"), replay.json().get("orderId"));
        assertEquals(first.json(), replay.json(), "повтор возвращает сохранённый ответ");
        assertEquals(1, ordersOf(buyer).size());
        assertEquals(1, NEIGHBOURS.calls("inventory").size());
        assertEquals(1, NEIGHBOURS.calls("payment").size());
        assertEquals(3, NEIGHBOURS.keysLeft(product), "ключи в резерв взяты один раз");
        assertEquals(1, events(text(first.json().get("orderId")), "order.created").size());

        Response other = create(token, product, 3, key);
        assertEquals(422, other.status(), other.body());
        assertEquals("idempotency-key-reuse", code(other));
        assertEquals(1, ordersOf(buyer).size());
    }

    @Tag("TC-511")
    @Test
    void aNewKeyIsANewOrderAndARequestWithoutAKeyIsRefused() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);
        String token = buyerToken(buyer);

        Response one = create(token, product, 1);
        Response two = create(token, product, 1);

        assertEquals(201, one.status(), one.body());
        assertEquals(201, two.status(), two.body());
        assertFalse(one.json().get("orderId").equals(two.json().get("orderId")));
        assertEquals(2, ordersOf(buyer).size());
        assertEquals(3, NEIGHBOURS.keysLeft(product));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", product.toString());
        body.put("quantity", 1);
        Response noKey = gateway.post(ORDERS, Json.write(body), StandHttp.bearer(token));
        assertEquals(400, noKey.status(), noKey.body());
        assertEquals("idempotency-key-required", code(noKey));
        assertEquals(2, ordersOf(buyer).size());
    }

    // ------------------------------------------------------------------------------------------------ TC-512

    @Tag("TC-512")
    @Test
    void theFourthUnpaidOrderIsRefusedAndNothingIsReservedForIt() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(10);
        String token = buyerToken(buyer);
        for (int i = 0; i < 3; i++) {
            assertEquals(201, create(token, product, 1).status());
        }

        Response fourth = create(token, product, 1);

        assertEquals(409, fourth.status(), fourth.body());
        assertEquals("unpaid-orders-limit", code(fourth));
        assertEquals(3, number(fourth.json().get("limit")));
        assertEquals(3, ordersOf(buyer).size(), "четвёртого заказа в базе нет");
        assertEquals(3, countOrders(buyer, "awaiting_payment"));
        assertEquals(3, NEIGHBOURS.calls("inventory").size(), "ключи для отказанного заказа не резервировались");
        assertEquals(3, NEIGHBOURS.calls("payment").size());
        assertEquals(7, NEIGHBOURS.keysLeft(product));
    }

    @Tag("TC-512")
    @Test
    void theLimitBelongsToTheBuyerAndACancelledOrderFreesTheSlot() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(10);
        String token = buyerToken(buyer);
        String firstId = null;
        for (int i = 0; i < 3; i++) {
            Response created = create(token, product, 1);
            assertEquals(201, created.status(), created.body());
            firstId = firstId == null ? text(created.json().get("orderId")) : firstId;
        }
        assertEquals("unpaid-orders-limit", code(create(token, product, 1)));

        assertEquals(201, create(buyerToken(UUID.randomUUID()), product, 1).status(), "другой покупатель лимитом не задет");

        assertEquals(1, db.update("update orders.orders set status = 'cancelled', cancel_reason = 'payment_declined' where id = ?", UUID.fromString(firstId)));
        assertEquals(201, create(token, product, 1).status(), "освободившееся место занимается новым заказом");
        assertEquals("unpaid-orders-limit", code(create(token, product, 1)));
    }

    @Tag("TC-512")
    @Test
    void ordersRefusedForTheStockDoNotCountAgainstTheLimit() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID empty = product(0);
        String token = buyerToken(buyer);
        for (int i = 0; i < 5; i++) {
            Response refused = create(token, empty, 1);
            assertEquals(409, refused.status(), refused.body());
            assertEquals("insufficient-stock", code(refused), "пятая попытка отказана остатком, а не лимитом");
        }
        assertEquals(5, countOrders(buyer, "cancelled"));
        assertEquals(0, countOrders(buyer, "created", "awaiting_payment"));
    }

    // ------------------------------------------------------------------------------------------------ TC-513

    @Tag("TC-513")
    @Test
    void notEnoughKeysCancelsTheOrderAsOutOfStockAndSaysHowManyAreLeft() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(2);

        Response response = create(buyerToken(buyer), product, 3);

        assertEquals(409, response.status(), response.body());
        assertEquals("insufficient-stock", code(response));
        assertEquals(2, number(response.json().get("available")));
        List<Map<String, Object>> orders = ordersOf(buyer);
        assertEquals(1, orders.size());
        assertEquals("cancelled", orders.get(0).get("status"));
        assertEquals("out_of_stock", orders.get(0).get("cancel_reason"));
        String orderId = orders.get(0).get("id").toString();
        List<Map<String, Object>> cancelled = events(orderId, "order.cancelled");
        assertEquals(1, cancelled.size());
        assertEquals("out_of_stock", data(cancelled.get(0)).get("cancelReason"));
        assertEquals(0, events(orderId, "order.created").size());
        assertEquals(0, NEIGHBOURS.calls("payment").size(), "платёжная сессия не открывалась");
        assertEquals(2, NEIGHBOURS.keysLeft(product));
    }

    @Tag("TC-513")
    @Test
    void anUnknownProductAndAProductThatIsNotOnSaleAreRefusedBeforeAnOrderExists() throws IOException {
        UUID buyer = UUID.randomUUID();
        String token = buyerToken(buyer);

        Response unknown = create(token, UUID.randomUUID(), 1);
        assertEquals(409, unknown.status(), unknown.body());
        assertEquals("product-unavailable", code(unknown));

        UUID hidden = NEIGHBOURS.product(seller, "Снят с продажи", PRICE, 5, "draft");
        Response notOnSale = create(token, hidden, 1);
        assertEquals(409, notOnSale.status(), notOnSale.body());
        assertEquals("product-unavailable", code(notOnSale));

        assertEquals(0, ordersOf(buyer).size());
        assertEquals(0, NEIGHBOURS.calls("inventory").size());
        assertEquals(0, NEIGHBOURS.calls("payment").size());
    }

    @Tag("TC-513")
    @Test
    void anUnavailableCatalogGives503WithRetryAfterAndNothingIsCreated() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);
        NEIGHBOURS.catalogIs(Mode.DOWN);

        Response response = create(buyerToken(buyer), product, 1);

        assertEquals(503, response.status(), response.body());
        assertEquals("dependency-unavailable", code(response));
        assertEquals("5", response.header("Retry-After"));
        assertEquals(0, ordersOf(buyer).size());
        assertEquals(0, NEIGHBOURS.calls("inventory").size());
    }

    @Tag("TC-513")
    @Test
    void anUnavailableInventoryCancelsTheOrderAsASystemErrorAndTheEventReleasesAReserveThatMayExist() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);
        NEIGHBOURS.inventoryIs(Mode.DOWN);

        Response response = create(buyerToken(buyer), product, 2);

        assertEquals(503, response.status(), response.body());
        assertEquals("dependency-unavailable", code(response));
        assertEquals("5", response.header("Retry-After"));
        List<Map<String, Object>> orders = ordersOf(buyer);
        assertEquals(1, orders.size());
        assertEquals("cancelled", orders.get(0).get("status"));
        assertEquals("system_error", orders.get(0).get("cancel_reason"));
        List<Map<String, Object>> cancelled = events(orders.get(0).get("id").toString(), "order.cancelled");
        assertEquals(1, cancelled.size());
        Map<String, Object> data = data(cancelled.get(0));
        assertEquals(product.toString(), data.get("productId"));
        assertEquals(2, number(data.get("quantity")));
        assertEquals(buyer.toString(), data.get("buyerId"));
        assertEquals(0, NEIGHBOURS.calls("payment").size());
    }

    @Tag("TC-513")
    @Test
    void aPaymentSessionThatCannotBeOpenedCancelsTheOrderAndTheBuyerCanTryAgainWhenTheServiceIsBack() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);
        String token = buyerToken(buyer);
        NEIGHBOURS.paymentIs(Mode.DOWN);

        Response response = create(token, product, 1);

        assertEquals(503, response.status(), response.body());
        assertEquals("payment-session-unavailable", code(response));
        assertEquals("5", response.header("Retry-After"));
        List<Map<String, Object>> orders = ordersOf(buyer);
        assertEquals("cancelled", orders.get(0).get("status"));
        assertEquals("payment_session_failed", orders.get(0).get("cancel_reason"));
        List<Map<String, Object>> cancelled = events(orders.get(0).get("id").toString(), "order.cancelled");
        assertEquals(1, cancelled.size(), "резерв снимается по событию order.cancelled");
        assertEquals("payment_session_failed", data(cancelled.get(0)).get("cancelReason"));
        assertEquals(0, events(orders.get(0).get("id").toString(), "order.created").size());
        assertEquals(0, countOrders(buyer, "created", "awaiting_payment"), "неудачная попытка место не занимает");

        NEIGHBOURS.paymentIs(Mode.OK);
        Response again = create(token, product, 1);
        assertEquals(201, again.status(), again.body());
        assertEquals("awaiting_payment", again.json().get("status"));
    }

    // ------------------------------------------------------------------------------------------------ TC-514

    @Tag("TC-514")
    @Test
    void theBuyerReadsTheOwnOrderAndTheSessionAndNobodyElseDoes() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Response created = create(buyerToken(buyer, "ivan@mail.example"), product(5), 1);
        assertEquals(201, created.status(), created.body());
        String orderId = text(created.json().get("orderId"));
        String token = buyerToken(buyer);

        Response own = read(token, ORDERS + "/" + orderId);
        assertEquals(200, own.status(), own.body());
        assertEquals("no-store", own.header("Cache-Control"));
        assertEquals("awaiting_payment", own.json().get("status"));
        assertEquals(orderId, own.json().get("orderId"));
        assertFalse(own.json().containsKey("paymentSession"), "ссылка на оплату отдаётся отдельной операцией");

        Response session = read(token, ORDERS + "/" + orderId + "/payment-session");
        assertEquals(200, session.status(), session.body());
        assertEquals("no-store", session.header("Cache-Control"));
        assertEquals(orderId, session.json().get("orderId"));
        assertEquals(map(created.json().get("paymentSession")).get("url"), session.json().get("url"));
        assertEquals(map(created.json().get("paymentSession")).get("expiresAt"), session.json().get("expiresAt"));

        for (String path : List.of(ORDERS + "/" + orderId, ORDERS + "/" + orderId + "/payment-session")) {
            Response foreign = read(buyerToken(stranger), path);
            assertEquals(404, foreign.status(), "чужой заказ неотличим от несуществующего (U1): " + path);
            assertEquals("not-found", code(foreign));
        }
        assertEquals(404, read(token, ORDERS + "/" + UUID.randomUUID()).status());
        assertEquals(400, read(token, ORDERS + "/" + orderId.toUpperCase()).status(), "идентификатор в нижнем регистре");
        assertEquals(400, read(token, ORDERS + "/not-a-uuid").status());
    }

    @Tag("TC-514")
    @Test
    void anSmsSessionSeesTheOrderWithAMaskedAddress() throws IOException {
        UUID buyer = UUID.randomUUID();
        Response created = create(buyerToken(buyer, "ivan@mail.example"), product(5), 1);
        assertEquals(201, created.status(), created.body());
        String sms = TOKENS.token().subject(buyer.toString()).scopes("orders.read").roles("buyer").amr("sms").build();

        Response response = read(sms, ORDERS + "/" + created.json().get("orderId"));

        assertEquals(200, response.status(), response.body());
        assertEquals("i***@mail.example", map(response.json().get("delivery")).get("address"));
        assertEquals(true, map(response.json().get("delivery")).get("masked"));
    }

    @Tag("TC-514")
    @Test
    void theSessionOfAnOrderThatIsNotAwaitingPaymentAnymoreIs409WithTheStatusAndNoLink() throws IOException {
        UUID buyer = UUID.randomUUID();
        Response created = create(buyerToken(buyer), product(5), 1);
        assertEquals(201, created.status(), created.body());
        String orderId = text(created.json().get("orderId"));
        assertEquals(1, db.update("update orders.orders set status = 'cancelled', cancel_reason = 'reservation_expired' where id = ?", UUID.fromString(orderId)));

        Response response = read(buyerToken(buyer), ORDERS + "/" + orderId + "/payment-session");

        assertEquals(409, response.status(), response.body());
        assertEquals("state-conflict", code(response));
        assertEquals("cancelled", response.json().get("currentStatus"));
        assertEquals("reservation_expired", response.json().get("cancelReason"));
        assertFalse(response.body().contains(text(map(created.json().get("paymentSession")).get("url"))), "ссылки на оплату в ответе нет");
    }

    // ------------------------------------------------------------------------------------------------ TC-515

    @Tag("TC-515")
    @Test
    void withoutATokenOrWithTheWrongRightsNothingIsCreatedAndNoNeighbourIsCalled() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", product.toString());
        body.put("quantity", 1);
        String json = Json.write(body);

        assertEquals(401, gateway.post(ORDERS, json, "Idempotency-Key", key()).status());

        String readOnly = TOKENS.token().subject(buyer.toString()).scopes("orders.read").roles("buyer").email("a@mail.example").build();
        Response noScope = create(readOnly, product, 1);
        assertEquals(403, noScope.status(), noScope.body());
        assertEquals("forbidden", code(noScope));

        String sellerToken = TOKENS.token().subject(buyer.toString()).scopes("orders.create").roles("seller").email("a@mail.example").build();
        assertEquals(403, create(sellerToken, product, 1).status());

        String sms = TOKENS.token().subject(buyer.toString()).scopes("orders.create", "orders.read").roles("buyer").amr("sms").email("a@mail.example").build();
        assertEquals(403, create(sms, product, 1).status(), "сессия по SMS заказ не оформляет (ST-02)");

        String noPhone = TOKENS.token().subject(buyer.toString()).scopes("orders.create").roles("buyer").phoneVerified(false).email("a@mail.example").build();
        Response unconfirmed = create(noPhone, product, 1);
        assertEquals(403, unconfirmed.status(), unconfirmed.body());
        assertEquals("phone-not-confirmed", code(unconfirmed));

        assertEquals(0, ordersOf(buyer).size());
        assertEquals(0, NEIGHBOURS.calls().size());
    }

    @Tag("TC-515")
    @Test
    void anInvalidBodyOrATokenWithoutAnAddressGets422AndNothingIsCreated() throws IOException {
        UUID buyer = UUID.randomUUID();
        UUID product = product(5);
        String token = buyerToken(buyer);
        String id = product.toString();

        List<String> bodies = List.of(
                "{\"productId\":\"" + id + "\",\"quantity\":0}",
                "{\"productId\":\"" + id + "\",\"quantity\":11}",
                "{\"productId\":\"" + id + "\",\"quantity\":1.5}",
                "{\"productId\":\"" + id + "\"}",
                "{\"quantity\":1}",
                "{\"productId\":\"не идентификатор\",\"quantity\":1}",
                "{\"productId\":\"" + id.toUpperCase() + "\",\"quantity\":1}",
                "{\"productId\":\"" + id + "\",\"quantity\":1,\"price\":1}");
        for (String body : bodies) {
            Response response = gateway.post(ORDERS, body, "Authorization", "Bearer " + token, "Idempotency-Key", key());
            assertEquals(422, response.status(), body + " -> " + response.body());
            assertEquals("validation-failed", code(response), body);
        }

        String noEmail = TOKENS.token().subject(buyer.toString()).scopes("orders.create", "orders.read").roles("buyer").build();
        Response response = create(noEmail, product, 1);
        assertEquals(422, response.status(), response.body());
        assertEquals("validation-failed", code(response));

        Response notJson = gateway.post(ORDERS, "{", "Authorization", "Bearer " + token, "Idempotency-Key", key());
        assertEquals(400, notJson.status(), notJson.body());
        assertEquals("bad-request", code(notJson));

        assertEquals(0, ordersOf(buyer).size());
        assertEquals(0, NEIGHBOURS.calls().size(), "до соседей ни один из этих запросов не дошёл");
        assertEquals(5, NEIGHBOURS.keysLeft(product));
    }
}
