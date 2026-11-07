package dgm.delivery.delivery.domain;

/** Состояния запроса ключа у системы продавца (таблица {@code delivery.seller_request}, ADR-028, раздел 1). */
public final class SellerRequestStatus {

    /** Запрос продавцу ещё не доставлен. */
    public static final String REQUESTED = "requested";
    /** Продавец ответил на запрос кодом 2xx. */
    public static final String ACCEPTED = "accepted";
    /** Ключи получены и лежат в хранилище ключей. */
    public static final String ANSWERED = "answered";
    /** Продавец отказал, подключение отключено или адрес не принят защитой. */
    public static final String FAILED = "failed";
    /** Окно ответа закрылось. */
    public static final String TIMEOUT = "timeout";

    private SellerRequestStatus() {
    }

    /** Запрос ещё ждёт ответа продавца: ответ и тайм-аут возможны только из этих двух состояний. */
    public static boolean open(String status) {
        return REQUESTED.equals(status) || ACCEPTED.equals(status);
    }
}
