package dgm.delivery.delivery.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Выдача, взятая диспетчером в аренду. {@code attemptNo} это число уже выполненных попыток (от 0 до 5): следующая попытка получит номер
 * {@code attemptNo + 1}. Адрес и название товара персональные данные и коммерческая информация: в {@link #toString()} их нет, чтобы не
 * попасть в журнал при случайной печати.
 *
 * @param id           идентификатор выдачи, он же идентификатор сообщения у провайдера
 * @param orderId      заказ
 * @param buyerId      покупатель
 * @param kind         {@code primary} или {@code repeat}
 * @param address      адрес из снимка заказа
 * @param productTitle название товара для письма
 * @param quantity     сколько ключей должно быть в письме (INV-19)
 * @param attemptNo    сколько попыток уже выполнено
 */
public record QueuedDelivery(UUID id, UUID orderId, UUID buyerId, String kind, String address, String productTitle, int quantity, int attemptNo) {

    public QueuedDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(buyerId, "buyerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(productTitle, "productTitle");
        if (quantity < 1) {
            throw new IllegalArgumentException("В выдаче хотя бы один ключ");
        }
        if (attemptNo < 0) {
            throw new IllegalArgumentException("Число попыток не бывает отрицательным");
        }
    }

    /** Номер попытки, которую нужно выполнить. */
    public int nextAttemptNo() {
        return attemptNo + 1;
    }

    @Override
    public String toString() {
        return "QueuedDelivery[id=" + id + ", orderId=" + orderId + ", kind=" + kind + ", quantity=" + quantity + ", attemptNo=" + attemptNo + "]";
    }
}
