package dgm.platform.support.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Обращение в очереди оператора (US-7.2, схема {@code StaffSupportTicket}): строка {@code support.ticket} и модель чтения заказа
 * {@code support.order_view} вместе (ADR-017, упрощённый CQRS). Значений ключей, адресов и кодов проверки в нём нет (INV-23).
 *
 * @param id             идентификатор обращения
 * @param orderId        заказ
 * @param orderNumber    короткий номер заказа, по которому оператора находит покупатель (FT-5.0)
 * @param buyerId        покупатель заказа
 * @param source         {@code buyer} или {@code system}
 * @param reason         причина обращения
 * @param status         {@code created}, {@code in_progress}, {@code resolved}
 * @param operatorId     оператор, взявший обращение; пусто у {@code created}
 * @param orderStatus    статус заказа по модели чтения
 * @param issuedAt       время первичной выдачи
 * @param deliveryStatus статус выдачи по модели чтения
 * @param createdAt      когда создано (по нему считается очередь, при возврате в очередь не меняется)
 * @param takenAt        когда взято в работу; пусто у {@code created}
 * @param resolvedAt     когда решено
 */
public record StaffTicket(UUID id, UUID orderId, long orderNumber, UUID buyerId, String source, String reason, String status,
                          Optional<UUID> operatorId, String orderStatus, Optional<Instant> issuedAt, Optional<String> deliveryStatus,
                          Instant createdAt, Optional<Instant> takenAt, Optional<Instant> resolvedAt) {
}
