package dgm.order.orders.service;

import dgm.kit.time.Timestamps;
import dgm.order.orders.domain.StoredOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** События заказа (тема {@code order.events}): имена и данные по AsyncAPI. Данные без персональных данных и без значений ключей. */
final class OrderEvents {

    static final String TOPIC = "order.events";
    static final String AGGREGATE = "Order";
    static final String CREATED = "order.created";
    static final String CANCELLED = "order.cancelled";
    static final int SCHEMA_VERSION = 1;

    private OrderEvents() {
    }

    /** {@code order.created}: SM-01/T2. Сроки и суммы зафиксированы в заказе и больше не меняются. */
    static Map<String, Object> created(StoredOrder o) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", o.id().toString());
        data.put("orderNumber", o.number());
        data.put("buyerId", o.buyerId().toString());
        data.put("sellerId", o.sellerId().toString());
        data.put("productId", o.productId().toString());
        data.put("quantity", o.quantity());
        data.put("unitPrice", money(o.unitPrice(), o.currency()));
        data.put("total", money(o.amount(), o.currency()));
        data.put("commission", money(o.commission(), o.currency()));
        data.put("reserveExpiresAt", Timestamps.format(o.reserveUntil()));
        data.put("paymentSessionExpiresAt", Timestamps.format(o.sessionUntil()));
        return data;
    }

    /** {@code order.cancelled}: SM-01/T3, T5, T6, T12. По нему {@code inventory-service} снимает активный резерв. */
    static Map<String, Object> cancelled(StoredOrder o, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", o.id().toString());
        data.put("buyerId", o.buyerId().toString());
        data.put("productId", o.productId().toString());
        data.put("quantity", o.quantity());
        data.put("cancelReason", reason);
        return data;
    }

    private static Map<String, Object> money(long amount, String currency) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        return money;
    }
}
