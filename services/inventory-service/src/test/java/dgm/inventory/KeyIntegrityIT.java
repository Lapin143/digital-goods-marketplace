package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/**
 * Ограничения базы, на которые опирается пул ключей (шаг 15 фазы 4, перенос проверок из {@code tools/docs-checks/db/test_db.py} в тесты
 * сервиса): INV-02 (привязка ключа к заказу по статусу), INV-04 (допустимые переходы SM-03), INV-01 (второй рубеж: закреплённый ключ не
 * перепривязывается), INV-03 (уникальность значения в товаре), одна активная версия ключа данных. Правила живут в базе, поэтому не
 * зависят от того, какой код пишет в таблицу. Проверки идут под ролью миграций (у рабочей роли все соединения заняты сервисом), на
 * ограничения роль не влияет.
 */
class KeyIntegrityIT extends KeyStandIT {

    /** Текст отказа базы: у ограничений в нём имя, у триггеров свой текст по DDL. */
    private static String refusal(String sql, Object... args) {
        DataAccessException e = assertThrows(DataAccessException.class, () -> db.update(sql, args), sql);
        return String.valueOf(e.getMostSpecificCause().getMessage());
    }

    private static void refused(String expectedText, String sql, Object... args) {
        String message = refusal(sql, args);
        assertTrue(message.contains(expectedText), "ожидали «" + expectedText + "», база ответила: " + message);
    }

    /** Свободный ключ в новом товаре; загрузка через API заводит ключ данных и товар в реестре. */
    private static UUID freeKey(UUID productId, Seller seller, String value) throws IOException {
        assertEquals(1, batch(seller, productId, value).json().get("accepted"));
        return db.queryForObject("select id from inventory.key where product_id = ? order by created_at desc limit 1", UUID.class, productId);
    }

    private static UUID reservation(UUID productId, UUID orderId) {
        UUID id = UUID.randomUUID();
        db.update("insert into inventory.reservation (id, order_id, product_id, quantity, expires_at) values (?, ?, ?, 1, now() + interval '15 minutes')",
                id, orderId, productId);
        return id;
    }

    @Tag("TC-411")
    @Test
    void aKeyIsBoundToAnOrderExactlyWhileItIsReservedOrIssued() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        UUID key = freeKey(productId, seller, "BIND-1");
        UUID order = UUID.randomUUID();
        UUID reservation = reservation(productId, order);

        refused("ck_key_binding", "update inventory.key set status = 'reserved' where id = ?", key);
        refused("ck_key_binding", "update inventory.key set order_id = ? where id = ?", order, key);
        assertEquals(1, db.update("update inventory.key set status = 'reserved', order_id = ?, reservation_id = ? where id = ?", order, reservation, key));
        assertEquals(1, db.update("update inventory.key set status = 'issued', issued_at = now() where id = ?", key));
    }

    @Tag("TC-411")
    @Test
    void onlyTheTransitionsOfSm03AreAllowedAndAnIssuedKeyNeverGoesBack() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        UUID key = freeKey(productId, seller, "SM03-1");
        UUID order = UUID.randomUUID();
        UUID reservation = reservation(productId, order);

        refused("free -> issued", "update inventory.key set status = 'issued', order_id = ?, reservation_id = ?, issued_at = now() where id = ?",
                order, reservation, key);
        refused("free -> voided", "update inventory.key set status = 'voided', issued_at = now() where id = ?", key);

        db.update("update inventory.key set status = 'reserved', order_id = ?, reservation_id = ? where id = ?", order, reservation, key);
        assertEquals(1, db.update("update inventory.key set status = 'free', order_id = null, reservation_id = null where id = ?", key),
                "резерв снят: reserved -> free разрешён");
        db.update("update inventory.key set status = 'reserved', order_id = ?, reservation_id = ? where id = ?", order, reservation, key);
        db.update("update inventory.key set status = 'issued', issued_at = now() where id = ?", key);

        refused("issued -> free", "update inventory.key set status = 'free', order_id = null, reservation_id = null, issued_at = null where id = ?", key);
        refused("issued -> reserved", "update inventory.key set status = 'reserved', issued_at = null where id = ?", key);
        assertEquals("issued", db.queryForObject("select status from inventory.key where id = ?", String.class, key));
    }

    @Tag("TC-411")
    @Test
    void aKeyBoundToAnOrderCannotBeRebound() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        UUID key = freeKey(productId, seller, "REB-1");
        UUID order = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID first = reservation(productId, order);
        UUID second = reservation(productId, other);
        db.update("update inventory.key set status = 'reserved', order_id = ?, reservation_id = ? where id = ?", order, first, key);

        refused("перепривязка запрещена", "update inventory.key set order_id = ?, reservation_id = ? where id = ?", other, second, key);
        assertEquals(order, db.queryForObject("select order_id from inventory.key where id = ?", UUID.class, key), "ключ остался за первым заказом");
    }

    @Tag("TC-411")
    @Test
    void theIdentifierAndTheProductOfAKeyNeverChange() throws IOException {
        Seller seller = new Seller();
        UUID productId = seller.product();
        UUID another = seller.product();
        UUID key = freeKey(productId, seller, "IMM-1");
        refused("не меняются", "update inventory.key set product_id = ? where id = ?", another, key);
        refused("не меняются", "update inventory.key set id = ? where id = ?", UUID.randomUUID(), key);
    }

    @Tag("TC-411")
    @Test
    void theSameValueTwiceInOneProductIsRefusedByTheUniqueIndexButIsFineInAnotherProduct() throws IOException {
        Seller seller = new Seller();
        UUID first = seller.product();
        UUID second = seller.product();
        freeKey(first, seller, "DUP-1");
        byte[] hmac = db.queryForObject("select hmac from inventory.key where product_id = ?", byte[].class, first);
        Integer dekId = db.queryForObject("select dek_id from inventory.key where product_id = ?", Integer.class, first);
        String insert = "insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) "
                + "values (?, ?, ?, decode('000000000000000000000000', 'hex'), decode(repeat('00', 17), 'hex'), ?)";

        refused("uq_key_product_id_hmac", insert, UUID.randomUUID(), first, dekId, hmac);
        assertEquals(1, db.update(insert, UUID.randomUUID(), second, dekId, hmac), "то же значение в другом товаре допустимо");
        refused("ck_key_hmac", insert, UUID.randomUUID(), first, dekId, new byte[] {1, 2});
    }

    @Tag("TC-411")
    @Test
    void thereIsExactlyOneActiveDataKey() throws IOException {
        Seller seller = new Seller();
        freeKey(seller.product(), seller, "DEK-1");
        refused("uq_data_key_active", "insert into inventory.data_key (wrapped_key, kek_version) values (decode(repeat('00', 60), 'hex'), 1)");
        assertEquals(1, db.queryForObject("select count(*) from inventory.data_key where status = 'active'", Integer.class));
    }
}
