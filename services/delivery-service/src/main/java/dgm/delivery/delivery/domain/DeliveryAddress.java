package dgm.delivery.delivery.domain;

/** Адрес в снимке выдачи после обезличивания пользователя (INV-44). */
public final class DeliveryAddress {

    /**
     * Что остаётся в снимке адреса после обезличивания. Зона {@code .invalid} зарезервирована (RFC 2606): письмо на такой адрес не уйдёт никогда,
     * а длина и вид проходят ограничения базы. Значение совпадает со значением {@code order-service}, чтобы обезличенные данные выглядели одинаково.
     */
    public static final String ANONYMIZED = "anonymized@anonymized.invalid";

    private DeliveryAddress() {
    }

    /** Обезличен ли адрес: такую выдачу не отправляют. */
    public static boolean isAnonymized(String address) {
        return ANONYMIZED.equals(address);
    }
}
