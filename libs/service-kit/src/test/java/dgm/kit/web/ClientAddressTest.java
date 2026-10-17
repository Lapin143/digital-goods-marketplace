package dgm.kit.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Адрес клиента для журнала аудита: первый адрес из заголовка шлюза, иначе адрес соединения, не длиннее предела схемы. */
class ClientAddressTest {

    private static MockHttpServletRequest request(String forwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/staff/seller-applications/x/approve");
        request.setRemoteAddr("10.0.0.5");
        if (forwarded != null) {
            request.addHeader("X-Forwarded-For", forwarded);
        }
        return request;
    }

    @Test
    void firstAddressOfTheGatewayHeaderIsUsed() {
        assertEquals("203.0.113.7", ClientAddress.of(request("203.0.113.7, 10.1.1.1")));
        assertEquals("203.0.113.7", ClientAddress.of(request("203.0.113.7")));
    }

    @Test
    void connectionAddressIsUsedWithoutTheHeader() {
        assertEquals("10.0.0.5", ClientAddress.of(request(null)));
        assertEquals("10.0.0.5", ClientAddress.of(request("  ")));
    }

    @Test
    void longValueIsCutToTheSchemaLimit() {
        assertEquals(ClientAddress.MAX_LENGTH, ClientAddress.of(request("a".repeat(300))).length());
    }
}
