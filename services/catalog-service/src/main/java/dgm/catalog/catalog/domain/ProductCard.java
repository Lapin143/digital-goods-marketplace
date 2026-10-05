package dgm.catalog.catalog.domain;

import java.util.UUID;

/** Карточка товара для сервиса заказов: статус, цена, продавец и способ выдачи. Доступность товара решает вызывающий по статусу. */
public record ProductCard(UUID id, UUID sellerId, String title, String status, long price, String currency, String issuanceMethod,
                          String productType) {
}
