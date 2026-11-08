package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Заказы продавца на стенде ({@code listSellerOrders}, OP-100, US-11.2): настоящий сервис, читатель под ролью {@code app_orders_read}, вызовы через
 * {@code api-gateway} по mTLS. Продавец видит только свои заказы, новые выше, ни при каком фильтре чужие не появляются (U3); в ответе нет покупателя,
 * его e-mail и адреса (INV-44): канарейка в адресе покупателя не должна встретиться в теле. Доступ по токену со вторым фактором и по личности API-ключа
 * (заголовки шлюза {@code X-Seller-Id} и {@code X-Auth-Kind}, Р-185).
 *
 * <p>Заказы оформляются через API (соседи это {@link Neighbours}), статусы «оплачен», «выдан», «отменён» и «возвращён» выставляются в базе под ролью
 * миграций, как в {@link OrderAddressIT}: переходы и события проверяют {@link OrderEventsIT} и модульные тесты.
 */
class SellerOrdersIT extends OrderStandIT {

    private static final String LIST = "/api/v1/seller/orders";
    private static final Set<String> FIELDS = Set.of("orderId", "orderNumber", "productId", "productTitle", "quantity", "unitPrice", "total", "commission",
            "status", "cancelReason", "issuanceMethod", "createdAt", "paidAt", "issuedAt", "refundedAt");

    /** Продавец с заказами, оформленными подряд: от старого к новому. */
    private record Shop(UUID seller, String canary, List<String> orders) {
    }

    private static Shop shop(boolean apiFirst) throws IOException {
        UUID seller = UUID.randomUUID();
        String canary = "canary-" + UUID.randomUUID() + "@mail.example";
        List<String> orders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            boolean api = apiFirst ? i != 2 : i == 2;
            UUID product = api ? NEIGHBOURS.apiProduct(seller, "Доступ " + i, 149_900L, 20) : NEIGHBOURS.product(seller, "Игра " + i, 149_900L, 20);
            Response response = create(buyerToken(UUID.randomUUID(), canary), product, 1 + i);
            assertEquals(201, response.status(), response.body());
            orders.add((String) response.json().get("orderId"));
        }
        return new Shop(seller, canary, orders);
    }

    private static void paid(String orderId) {
        assertEquals(1, db.update("update orders.orders set status = 'paid', paid_at = now() where id = ?", UUID.fromString(orderId)));
    }

    private static void issued(String orderId) {
        paid(orderId);
        assertEquals(1, db.update("update orders.orders set status = 'issued', issued_at = now() where id = ?", UUID.fromString(orderId)));
    }

    private static void refundedAfterTimeout(String orderId) {
        paid(orderId);
        UUID id = UUID.fromString(orderId);
        assertEquals(1, db.update("update orders.orders set status = 'cancelled', cancel_reason = 'seller_api_timeout' where id = ?", id));
        assertEquals(1, db.update("update orders.orders set status = 'refunded', refunded_at = now() where id = ?", id));
    }

    private static Response list(UUID seller, String query) throws IOException {
        return read(sellerToken(seller), LIST + query);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Response response) {
        assertEquals(200, response.status(), response.body());
        return (List<Map<String, Object>>) (List<?>) response.json().get("items");
    }

    private static List<String> ids(Response response) {
        return items(response).stream().map(item -> (String) item.get("orderId")).toList();
    }

    private static List<String> newestFirst(List<String> oldestFirst) {
        List<String> copy = new ArrayList<>(oldestFirst);
        java.util.Collections.reverse(copy);
        return copy;
    }

    // ------------------------------------------------------------------------------------------------ TC-961

    @Tag("TC-961")
    @Test
    void aSellerSeesOnlyTheirOwnOrdersNewestFirstWithoutAnyDataOfTheBuyer() throws IOException {
        Shop mine = shop(true);
        Shop other = shop(false);
        issued(mine.orders().get(0));
        refundedAfterTimeout(mine.orders().get(1));

        Response response = list(mine.seller(), "");

        assertEquals(200, response.status(), response.body());
        assertEquals("no-store", response.header("Cache-Control"));
        assertEquals(newestFirst(mine.orders()), ids(response));
        for (Map<String, Object> item : items(response)) {
            assertEquals(FIELDS, item.keySet());
        }
        assertFalse(response.body().contains(mine.canary()), "адрес покупателя продавцу не отдаётся (INV-44)");
        for (String forbidden : List.of("buyerId", "email", "address", "delivery", "reserveExpiresAt", "paymentSessionExpiresAt")) {
            assertFalse(response.body().contains("\"" + forbidden + "\""), forbidden);
        }
        for (String foreign : other.orders()) {
            assertFalse(response.body().contains(foreign), "чужой заказ " + foreign);
        }

        Map<String, Object> first = items(response).get(2);
        assertEquals(mine.orders().get(0), first.get("orderId"));
        assertEquals("issued", first.get("status"));
        assertEquals("seller_api", first.get("issuanceMethod"));
        assertEquals(149_900, ((Number) ((Map<?, ?>) first.get("unitPrice")).get("amount")).intValue());
        assertEquals(149_900, ((Number) ((Map<?, ?>) first.get("total")).get("amount")).intValue());
        assertEquals(2_998, ((Number) ((Map<?, ?>) first.get("commission")).get("amount")).intValue());
        assertNotNull(first.get("paidAt"));
        assertNotNull(first.get("issuedAt"));
        assertNull(first.get("refundedAt"));

        Map<String, Object> timedOut = items(response).get(1);
        assertEquals("refunded", timedOut.get("status"));
        assertEquals("seller_api_timeout", timedOut.get("cancelReason"));
        assertNotNull(timedOut.get("refundedAt"));

        Map<String, Object> unpaid = items(response).get(0);
        assertEquals("awaiting_payment", unpaid.get("status"));
        assertEquals("key_pool", unpaid.get("issuanceMethod"));
        assertNull(unpaid.get("paidAt"));

        assertEquals(newestFirst(other.orders()), ids(list(other.seller(), "")), "у другого продавца свой список");
    }

    @Tag("TC-961")
    @Test
    void theCursorWalksThroughEveryOrderOnceAndStatusAndPeriodFiltersNarrowTheList() throws IOException {
        Shop mine = shop(true);
        issued(mine.orders().get(0));
        paid(mine.orders().get(1));

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Response page = list(mine.seller(), "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor));
            walked.addAll(ids(page));
            Object next = ((Map<?, ?>) page.json().get("page")).get("nextCursor");
            cursor = (String) next;
            assertTrue(++pages < 5, "курсор зациклился");
        } while (cursor != null);
        assertEquals(newestFirst(mine.orders()), walked);

        assertEquals(newestFirst(List.of(mine.orders().get(0), mine.orders().get(1))), ids(list(mine.seller(), "?status=paid,issued")));
        assertEquals(List.of(mine.orders().get(2)), ids(list(mine.seller(), "?status=awaiting_payment")));
        assertEquals(List.of(), ids(list(mine.seller(), "?status=refunded")));

        Response all = list(mine.seller(), "");
        String middle = (String) items(all).get(1).get("createdAt");
        assertEquals(List.of(mine.orders().get(2), mine.orders().get(1)), ids(list(mine.seller(), "?createdFrom=" + middle)), "начало периода включено");
        assertEquals(List.of(mine.orders().get(0)), ids(list(mine.seller(), "?createdTo=" + middle)), "конец периода не включён");
    }

    @Tag("TC-961")
    @Test
    void anApiKeyIdentityReadsTheOrdersOfItsOwnProfileThroughTheGatewayHeaders() throws IOException {
        Shop mine = shop(true);
        Shop other = shop(false);

        Response asKey = gateway.get(LIST, "X-Seller-Id", mine.seller().toString(), "X-Auth-Kind", "api-key");

        assertEquals(200, asKey.status(), asKey.body());
        assertEquals(newestFirst(mine.orders()), ids(asKey));
        Response asOtherKey = gateway.get(LIST, "X-Seller-Id", other.seller().toString(), "X-Auth-Kind", "api-key");
        assertEquals(newestFirst(other.orders()), ids(asOtherKey));
    }

    @Tag("TC-961")
    @Test
    void anotherSellerCannotBeNamedAndTokensWithoutTheRightsAreRefused() throws IOException {
        Shop mine = shop(true);
        UUID someone = UUID.randomUUID();

        Response named = list(someone, "?sellerId=" + mine.seller());
        assertEquals(422, named.status(), named.body());
        assertEquals(List.of(), ids(list(someone, "")), "у продавца без заказов список пуст, чужие не подмешиваются");

        String buyer = buyerToken(UUID.randomUUID());
        assertEquals(403, read(buyer, LIST).status());

        String noSecondFactor = TOKENS.token().subject(UUID.randomUUID().toString()).scopes("seller.catalog").roles("seller").amr("pwd")
                .sellerId(mine.seller().toString()).build();
        Response factor = read(noSecondFactor, LIST);
        assertEquals(403, factor.status(), factor.body());
        assertEquals("second-factor-required", code(factor));

        String noProfile = TOKENS.token().subject(UUID.randomUUID().toString()).scopes("seller.catalog").roles("seller").amr("pwd", "otp").build();
        assertEquals(403, read(noProfile, LIST).status(), "в токене нет профиля продавца");

        assertEquals(401, gateway.get(LIST).status());
        assertEquals(401, gateway.get(LIST, StandHttp.bearer("not-a-token")).status());
    }
}
