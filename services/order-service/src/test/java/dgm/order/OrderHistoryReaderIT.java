package dgm.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.boot.Modules;
import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.order.orders.repository.JdbcOrderRepository;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Читатель истории ({@code history-reader}, ADR-017, Р-163) на стенде: история и карточка заказа читаются под ролью {@code app_orders_read}, у которой в базе
 * только {@code SELECT} на {@code orders.orders}. Проверяется то, что делает разделение настоящим: соединения читателя открыты именно под этой ролью, пул
 * модуля {@code orders-read} вмещает ровно два соединения (предел роли), а запись и чтение служебных таблиц под ней база отвергает. Поведение
 * {@code listOrders} и карточки заказа не меняется: их сценарии проходят в {@link OrderServiceIT} и {@link OrderCreationIT} как регрессия.
 */
class OrderHistoryReaderIT extends OrderStandIT {

    private static final String BUYER_A = "0199e0a0-0000-4000-8000-0000000000a1";
    private static final String O301 = "0199e0a0-0000-7000-8000-000000000301";

    @Autowired
    private Modules modules;

    private static String buyer() {
        return TOKENS.token().subject(BUYER_A).scopes("orders.read").roles("buyer").amr("pwd").build();
    }

    private static boolean permissionDenied(DataAccessException e) {
        return e.getMostSpecificCause() instanceof SQLException sql && "42501".equals(sql.getSQLState());
    }

    @Tag("TC-530")
    @Test
    void theHistoryAndTheCardAreReadUnderTheReadOnlyRole() throws IOException {
        assertTrue(modules.names().contains(JdbcOrderRepository.READ_MODULE), "модуль читателя истории объявлен в dgm.db.modules");
        assertEquals("orders", modules.defaultModule(), "модуль по умолчанию остаётся за записью: публикатор Outbox и охранник ключей работают под app_orders");

        Response list = gateway.get("/api/v1/orders", StandHttp.bearer(buyer()));
        Response card = gateway.get("/api/v1/orders/" + O301, StandHttp.bearer(buyer()));

        assertEquals(200, list.status(), list.body());
        assertEquals(200, card.status(), card.body());
        assertEquals(O301, card.json().get("orderId"));
        Integer connections = db.queryForObject("select count(*) from pg_stat_activity where usename = 'app_orders_read' and datname = 'order_db'",
                Integer.class);
        assertNotNull(connections);
        assertTrue(connections >= 1 && connections <= 2, "соединения читателя истории открыты под app_orders_read и не больше предела роли: " + connections);
        Integer writers = db.queryForObject("select count(*) from pg_stat_activity where usename = 'app_orders' and datname = 'order_db'", Integer.class);
        assertNotNull(writers);
        assertTrue(writers >= 1, "контроль: записывающая часть работает под app_orders: " + writers);
    }

    @Tag("TC-530")
    @Test
    void theReadRoleCannotWriteNorReadTheServiceTables() throws IOException {
        JdbcTemplate reader = modules.jdbc(JdbcOrderRepository.READ_MODULE);
        UUID id = UUID.fromString(O301);
        Object before = order(O301).get("delivery_address");
        Object version = order(O301).get("version");

        Integer visible = reader.queryForObject("select count(*) from orders.orders where id = ?", Integer.class, id);
        assertEquals(1, visible, "чтение таблицы заказов разрешено");

        List<DataAccessException> refused = List.of(
                assertThrows(DataAccessException.class, () -> reader.update("update orders.orders set delivery_address = 'hacked@mail.example' where id = ?", id)),
                assertThrows(DataAccessException.class, () -> reader.update("update orders.orders set status = 'refunded' where id = ?", id)),
                assertThrows(DataAccessException.class, () -> reader.update("delete from orders.orders where id = ?", id)),
                assertThrows(DataAccessException.class, () -> reader.update("insert into orders.orders (id, buyer_id, seller_id, product_id, product_title, quantity, "
                        + "unit_price, amount, commission_rate_bp, commission, delivery_address) values (?, ?, ?, ?, 'Игра', 1, 100, 100, 200, 2, 'x@mail.example')",
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())),
                assertThrows(DataAccessException.class, () -> reader.queryForObject("select count(*) from public.outbox", Integer.class)),
                assertThrows(DataAccessException.class, () -> reader.queryForObject("select count(*) from public.idempotency_key", Integer.class)),
                assertThrows(DataAccessException.class, () -> reader.update("insert into public.outbox (event_id) values (?)", UUID.randomUUID())));
        for (DataAccessException e : refused) {
            assertTrue(permissionDenied(e), "база отвергает запрос по правам (42501): " + e.getMostSpecificCause());
        }

        assertEquals(before, order(O301).get("delivery_address"), "заказ не изменился");
        assertEquals(version, order(O301).get("version"));
    }
}
