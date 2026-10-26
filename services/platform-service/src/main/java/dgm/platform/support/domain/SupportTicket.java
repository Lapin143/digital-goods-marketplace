package dgm.platform.support.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Обращение в поддержку по одному заказу (SM-07, схема {@code support.ticket}). Значений ключей, адресов и кодов проверки в нём нет
 * (INV-23, FT-7.5): покупателю и оператору отдаётся только состояние обращения.
 *
 * @param id         идентификатор обращения
 * @param orderId    заказ
 * @param buyerId    покупатель заказа
 * @param source     {@code buyer} или {@code system}
 * @param reason     {@code key_not_received} у покупателя; {@code not_delivered}, {@code attempts_exhausted}, {@code delivery_overdue} у системы
 * @param status     {@code created}, {@code in_progress}, {@code resolved}
 * @param createdAt  когда создано
 * @param resolvedAt когда решено (у решённого обращения)
 * @param resolvedBy {@code operator} или {@code system} (у решённого обращения)
 * @param action     {@code resend} или {@code email_change}; пусто, если решено системой по факту доставки
 * @param emailChange состояние смены e-mail (адрес только с маской); пусто, пока смена по обращению не начата (шаг 14, Р-169)
 */
public record SupportTicket(UUID id, UUID orderId, UUID buyerId, String source, String reason, String status, Instant createdAt,
                            Optional<Instant> resolvedAt, Optional<String> resolvedBy, Optional<String> action,
                            Optional<EmailChangeView> emailChange) {

    public static final String CREATED = "created";
    public static final String IN_PROGRESS = "in_progress";
    public static final String RESOLVED = "resolved";
    /** Значения фильтра {@code status} (схема TicketStatus). */
    public static final Set<String> STATUSES = Set.of(CREATED, IN_PROGRESS, RESOLVED);
    public static final String BUYER = "buyer";
    public static final String KEY_NOT_RECEIVED = "key_not_received";
}
