package dgm.platform.support.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * События модуля по схемам AsyncAPI ({@code TicketCreatedData}, {@code TicketResolvedData}). Лишних полей нет: схемы закрыты
 * ({@code additionalProperties: false}). Ни адресов, ни значений ключей, ни кодов проверки в событиях нет (INV-23).
 */
public final class SupportEvents {

    public static final String TOPIC = "support.events";
    public static final String AGGREGATE = "Ticket";
    public static final String CREATED = "ticket.created";
    public static final String RESOLVED = "ticket.resolved";

    private SupportEvents() {
    }

    /** {@code ticket.created}: обращение создано и встало в очередь поддержки (SM-07/T1). */
    public static Map<String, Object> created(UUID ticketId, UUID orderId, UUID buyerId, String source, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticketId.toString());
        data.put("orderId", orderId.toString());
        data.put("buyerId", buyerId.toString());
        data.put("source", source);
        data.put("reason", reason);
        return data;
    }

    /** {@code ticket.resolved}: обращение решено (SM-07/T4); {@code action} у решения системы по факту доставки не заполняется. */
    public static Map<String, Object> resolved(UUID ticketId, UUID orderId, UUID buyerId, String resolvedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticketId.toString());
        data.put("orderId", orderId.toString());
        data.put("buyerId", buyerId.toString());
        data.put("resolvedBy", resolvedBy);
        return data;
    }
}
