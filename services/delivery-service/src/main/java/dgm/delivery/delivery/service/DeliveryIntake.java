package dgm.delivery.delivery.service;

import dgm.delivery.delivery.domain.SellerRequestSeed;
import dgm.delivery.delivery.repository.DeliveryRepository;
import dgm.delivery.delivery.repository.SellerRequestRepository;
import dgm.kit.id.Uuids;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Приём оплаты (SM-06/T1, INV-18): по {@code order.paid} создаёт первичную выдачу в очереди и открывает контроль доставки. Обе записи идут в
 * транзакции потребителя вместе с отметкой {@code processed_event}, поэтому класс своей транзакции не открывает. Повтор события второй
 * выдачи не создаёт: первичная выдача заказа одна (уникальный индекс), контроль один (первичный ключ).
 */
public final class DeliveryIntake {

    /** Наименьший запас срока ответа, если событие дошло позже окна: срок должен быть позже создания запроса (ограничение таблицы), остальное закроет тайм-аут. */
    private static final Duration MIN_LEFT = Duration.ofSeconds(1);

    private final DeliveryRepository deliveries;
    private final SellerRequestRepository sellerRequests;
    private final SellerSettings sellerSettings;
    private final DeliverySettings settings;
    private final Clock clock;

    public DeliveryIntake(DeliveryRepository deliveries, DeliverySettings settings, Clock clock) {
        this(deliveries, null, SellerSettings.defaults(), settings, clock);
    }

    /**
     * @param sellerRequests запросы продавцам (R2): для заказа с выдачей по API вместе с выдачей создаётся запрос; {@code null} только в модульных
     *                       тестах заказов по ключам
     */
    public DeliveryIntake(DeliveryRepository deliveries, SellerRequestRepository sellerRequests, SellerSettings sellerSettings, DeliverySettings settings,
                          Clock clock) {
        this.deliveries = deliveries;
        this.sellerRequests = sellerRequests;
        this.sellerSettings = sellerSettings;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * @param paidAt когда заказ оплачен: от него отсчитывается окно контроля (оплата за пределами окна, пришедшая после простоя, просрочена сразу)
     * @return {@code true}: выдача создана; {@code false}: у заказа уже есть первичная выдача
     */
    public boolean accept(UUID orderId, UUID buyerId, String address, String productTitle, int quantity, Instant paidAt) {
        return accept(orderId, buyerId, address, productTitle, quantity, paidAt, null);
    }

    /**
     * Приём оплаты заказа, у которого товар выдаётся по API продавца (SM-06/T1, ADR-028, шаг 12.1): вместе с первичной выдачей и контролем создаётся
     * запрос {@code requested} со сроком ответа «время оплаты плюс окно ответа». Диспетчер не берёт выдачу в отправку, пока запрос не получит ответ
     * (условие в запросе выбора). Событие, обработанное позже окна, получает срок через секунду после создания запроса: его сразу закроет тайм-аут.
     *
     * @param sellerRequest данные для запроса продавцу; {@code null}: заказ по ключам, запроса нет
     */
    public boolean accept(UUID orderId, UUID buyerId, String address, String productTitle, int quantity, Instant paidAt, SellerRequestSeed sellerRequest) {
        Instant now = clock.instant();
        UUID deliveryId = Uuids.v7(clock);
        boolean created = deliveries.insertPrimary(deliveryId, orderId, buyerId, address, productTitle, quantity, now);
        if (created) {
            deliveries.insertWatch(orderId, paidAt.plus(settings.controlWindow()), now);
            if (sellerRequest != null) {
                if (sellerRequests == null) {
                    throw new IllegalStateException("Заказ по API принят без хранилища запросов продавцам");
                }
                Instant deadline = paidAt.plus(sellerSettings.response());
                Instant earliest = now.plus(MIN_LEFT);
                sellerRequests.insert(deliveryId, Uuids.v7(clock), sellerRequest, deadline.isBefore(earliest) ? earliest : deadline, now);
            }
        }
        return created;
    }
}
