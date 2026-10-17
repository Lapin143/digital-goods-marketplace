package dgm.platform.identity.sellerrole;

import dgm.kit.events.FatalEventException;
import java.util.Map;
import java.util.UUID;

/**
 * Нужная часть данных события {@code seller.approved} ({@code SellerApprovedData}): кому и какой профиль. Нарушение схемы неисправимо,
 * повтор события его не изменит, поэтому сразу уходит в DLQ. Сообщения об ошибках называют поле, но не значение.
 *
 * @param userId   пользователь, подавший заявку: ему назначается роль
 * @param sellerId профиль продавца в каталоге (SellerID), он же значение атрибута {@code seller_id} в токене
 */
public record SellerApproval(UUID userId, UUID sellerId) {

    static SellerApproval parse(Map<String, Object> data) {
        return new SellerApproval(uuid(data, "userId"), uuid(data, "sellerId"));
    }

    private static UUID uuid(Map<String, Object> data, String field) {
        if (!(data.get(field) instanceof String text) || text.length() != 36) {
            throw new FatalEventException("Поле " + field + " обязательно и должно быть UUID");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new FatalEventException("Поле " + field + " должно быть UUID");
        }
    }
}
