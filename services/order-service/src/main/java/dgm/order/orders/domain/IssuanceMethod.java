package dgm.order.orders.domain;

import java.util.Set;

/** Способ выдачи товара (Р-189): значения совпадают с {@code ck_orders_issuance_method} и полем {@code issuanceMethod} события {@code order.paid}. */
public final class IssuanceMethod {

    /** Ключи из пула, который продавец загрузил заранее. */
    public static final String KEY_POOL = "key_pool";
    /** Ключ запрашивается у системы продавца после оплаты (R2, Р-190). */
    public static final String SELLER_API = "seller_api";

    public static final Set<String> ALL = Set.of(KEY_POOL, SELLER_API);

    private IssuanceMethod() {
    }

    /** Способ из списка известных; {@code null} и незнакомое значение это не способ ({@code Set.of} на {@code null} бросает исключение). */
    public static boolean isKnown(String method) {
        return method != null && ALL.contains(method);
    }
}
