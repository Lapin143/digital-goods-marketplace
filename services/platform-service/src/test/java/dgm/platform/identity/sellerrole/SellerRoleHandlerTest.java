package dgm.platform.identity.sellerrole;

import static dgm.platform.identity.sellerrole.SellerRoleTestKit.SELLER;
import static dgm.platform.identity.sellerrole.SellerRoleTestKit.USER;
import static dgm.platform.identity.sellerrole.SellerRoleTestKit.data;
import static dgm.platform.identity.sellerrole.SellerRoleTestKit.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.FatalEventException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Контракт обработчика {@code seller-role-handler} (AsyncAPI, x-consumer) и разбор данных события {@code seller.approved}. */
class SellerRoleHandlerTest {

    private SellerRoleTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new SellerRoleTestKit();
    }

    @Test
    void handlerDeclaresTheApprovalEventOfTheCatalogTopicAndTheIdentityModule() {
        assertEquals("seller-role-handler", kit.handler.name());
        assertEquals("catalog.events", kit.handler.topic());
        assertEquals(Set.of("seller.approved"), kit.handler.types());
        assertEquals("identity", kit.handler.module());
        assertEquals(1, kit.handler.maxSchemaVersion());
    }

    @Test
    void extraFieldsOfTheSchemaAreIgnored() {
        kit.user(USER, "buyer");
        kit.deliver(event(data(USER, SELLER)));
        assertEquals("seller", kit.accounts.find(USER).orElseThrow().role());
    }

    @Test
    void brokenDataGoesStraightToTheDeadLetterQueueWithoutNamingTheValue() {
        kit.user(USER, "buyer");
        List<Broken> broken = List.of(new Broken("userId", null), new Broken("sellerId", null), new Broken("userId", 42),
                new Broken("sellerId", List.of("a")), new Broken("userId", "не-uuid-секретное-значение"),
                new Broken("sellerId", "ZZZZZZZZ-0000-4000-8000-000000000001"));
        for (Broken bad : broken) {
            FatalEventException e = assertThrows(FatalEventException.class, () -> kit.deliver(event(bad.data())), bad.toString());
            assertTrue(e.getMessage().contains(bad.field()), e.getMessage());
            assertFalse(e.getMessage().contains("секретное"), "значения в сообщении нет");
        }
        assertEquals(List.of(), kit.tx.events());
        assertEquals("buyer", kit.accounts.find(USER).orElseThrow().role());
    }

    /** Событие с одним испорченным полем: значение {@code null} убирает поле. */
    private record Broken(String field, Object value) {

        Map<String, Object> data() {
            Map<String, Object> data = SellerRoleTestKit.data(USER, SELLER);
            if (value == null) {
                data.remove(field);
            } else {
                data.put(field, value);
            }
            return data;
        }
    }
}
