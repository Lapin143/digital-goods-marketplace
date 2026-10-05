package dgm.order.orders.domain;

/** Показ адреса доставки: для сессии по SMS адрес маскируется (FT-1.7, OP-58): {@code buyer@mail.example} становится {@code b***@mail.example}. */
public final class DeliveryAddress {

    private DeliveryAddress() {
    }

    public static String mask(String address) {
        int at = address.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return address.substring(0, address.offsetByCodePoints(0, 1)) + "***" + address.substring(at);
    }
}
