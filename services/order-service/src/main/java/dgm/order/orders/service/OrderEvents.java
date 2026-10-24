package dgm.order.orders.service;

import dgm.kit.time.Timestamps;
import dgm.order.orders.domain.StoredOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** События заказа (тема {@code order.events}): имена и данные по AsyncAPI. Данные без персональных данных и без значений ключей. */
final class OrderEvents {

    static final String TOPIC = "order.events";
    static final String AGGREGATE = "Order";
    static final String CREATED = "order.created";
    static final String CANCELLED = "order.cancelled";
    static final String PAID = "order.paid";
    static final String ISSUED = "order.issued";
    static final String REFUNDED = "order.refunded";
    static final String REFUND_REQUESTED = "order.refund-requested";
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

    /**
     * {@code order.paid}: SM-01/T4 и T7. Единственное событие с адресом доставки (персональные данные): «Выдача» отправляет ключ без запроса в
     * «Заказы». {@code latePayment}: оплата пришла после отмены и ключи резервировались заново.
     */
    static Map<String, Object> paid(StoredOrder o, boolean latePayment) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("channel", "email");
        delivery.put("address", o.deliveryAddress());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", o.id().toString());
        data.put("orderNumber", o.number());
        data.put("buyerId", o.buyerId().toString());
        data.put("sellerId", o.sellerId().toString());
        data.put("productId", o.productId().toString());
        data.put("productTitle", o.productTitle());
        data.put("quantity", o.quantity());
        data.put("total", money(o.amount(), o.currency()));
        data.put("commission", money(o.commission(), o.currency()));
        data.put("paidAt", Timestamps.format(o.paidAt()));
        data.put("latePayment", latePayment);
        data.put("delivery", delivery);
        return data;
    }

    /** {@code order.issued}: SM-01/T9, один раз за жизнь заказа (INV-17). От него отсчитываются окна обращения, спора и удержание. */
    static Map<String, Object> issued(StoredOrder o, UUID deliveryId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", o.id().toString());
        data.put("orderNumber", o.number());
        data.put("buyerId", o.buyerId().toString());
        data.put("sellerId", o.sellerId().toString());
        data.put("productId", o.productId().toString());
        data.put("total", money(o.amount(), o.currency()));
        data.put("commission", money(o.commission(), o.currency()));
        data.put("deliveryId", deliveryId.toString());
        data.put("issuedAt", Timestamps.format(o.issuedAt()));
        return data;
    }

    /** {@code order.refunded}: SM-01/T8. Деньги вернулись покупателю; по событию уходит письмо о возврате (FT-11.0), номер заказа нужен ему (Р-153). */
    static Map<String, Object> refunded(StoredOrder o, String refundReason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", o.id().toString());
        data.put("orderNumber", o.number());
        data.put("buyerId", o.buyerId().toString());
        data.put("sellerId", o.sellerId().toString());
        data.put("total", money(o.amount(), o.currency()));
        data.put("refundReason", refundReason);
        return data;
    }

    /** {@code order.refund-requested}: команда {@code payment-service} вернуть деньги, всегда на всю сумму заказа (FT-8.4, ADR-015). */
    static Map<String, Object> refundRequested(StoredOrder o, String refundReason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", o.id().toString());
        data.put("amount", money(o.amount(), o.currency()));
        data.put("refundReason", refundReason);
        return data;
    }

    private static Map<String, Object> money(long amount, String currency) {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("amount", amount);
        money.put("currency", currency);
        return money;
    }
}
