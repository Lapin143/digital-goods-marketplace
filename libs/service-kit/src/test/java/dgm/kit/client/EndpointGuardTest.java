package dgm.kit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.client.EndpointGuard.Reason;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Защита от SSRF (T-46, правило 2 руководства Ф5): двенадцать «плохих» адресов, три хороших, отображённый IPv4, подмена DNS,
 * разрешённый список стенда. Все отказы происходят до сетевого вызова: проверка сама ничего не открывает.
 */
class EndpointGuardTest {

    /** Подставной DNS: имя в список адресов, адреса записаны литералами, поэтому настоящий DNS не нужен. Считает вопросы. */
    private static final class FakeDns implements EndpointGuard.Resolver {
        private final Map<String, List<String>> answers;
        final List<String> asked = new ArrayList<>();

        FakeDns(Map<String, List<String>> answers) {
            this.answers = answers;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            asked.add(host);
            List<String> literals = answers.get(host);
            if (literals == null) {
                throw new UnknownHostException(host);
            }
            InetAddress[] out = new InetAddress[literals.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = InetAddress.getByName(literals.get(i));
            }
            return out;
        }
    }

    private static Reason reasonOf(EndpointGuard guard, String url) {
        return assertThrows(EndpointGuard.RejectedException.class, () -> guard.check(url)).reason();
    }

    // Двенадцать «плохих» адресов: имя продавца ведёт во внутреннюю сеть
    private static final Map<String, String> BAD = Map.ofEntries(
            Map.entry("loopback.example", "127.0.0.1"),
            Map.entry("loopback-range.example", "127.8.9.10"),
            Map.entry("private10.example", "10.1.2.3"),
            Map.entry("private172.example", "172.16.5.5"),
            Map.entry("private172-top.example", "172.31.255.254"),
            Map.entry("private192.example", "192.168.1.10"),
            Map.entry("carrier-nat.example", "100.64.0.1"),
            Map.entry("metadata.example", "169.254.169.254"),
            Map.entry("any.example", "0.0.0.0"),
            Map.entry("ip6-loopback.example", "::1"),
            Map.entry("ip6-unique-local.example", "fd12:3456:789a::1"),
            Map.entry("ip6-link-local.example", "fe80::1"));

    @Test
    void twelveBadAddressesAreRejected() {
        assertEquals(12, BAD.size());
        FakeDns dns = new FakeDns(toLists(BAD));
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        for (String host : BAD.keySet()) {
            assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://" + host + "/hook"), host);
        }
    }

    @Test
    void threeGoodAddressesPassAndAreReturnedAsTheCheckedAddress() throws UnknownHostException {
        FakeDns dns = new FakeDns(Map.of(
                "seller.example", List.of("93.184.216.34"),
                "dns-google.example", List.of("8.8.8.8"),
                "seller6.example", List.of("2606:2800:220:1:248:1893:25c8:1946")));
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        EndpointGuard.Target first = guard.check("https://seller.example/hook?a=1&b=2");
        assertEquals("seller.example", first.host());
        assertEquals(443, first.port());
        assertEquals(InetAddress.getByName("93.184.216.34"), first.address());
        assertEquals("/hook?a=1&b=2", first.target());
        assertEquals(8443, guard.check("https://dns-google.example:8443/").port());
        assertEquals("/", guard.check("https://dns-google.example:8443").target());
        assertTrue(guard.check("https://seller6.example/x").address() instanceof Inet6Address);
    }

    @Test
    void ipv4MappedIntoIpv6IsCheckedAsIpv4() throws UnknownHostException {
        // Java сам превращает ::ffff:127.0.0.1 в IPv4; адрес, собранный как IPv6, проверяется по байтам
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = 127;
        mapped[15] = 1;
        assertTrue(EndpointGuard.forbidden(Inet6Address.getByAddress("mapped", mapped, 0)));
        mapped[12] = 10;
        assertTrue(EndpointGuard.forbidden(Inet6Address.getByAddress("mapped", mapped, 0)));
        mapped[12] = (byte) 169;
        mapped[13] = (byte) 254;
        mapped[14] = (byte) 169;
        mapped[15] = (byte) 254;
        assertTrue(EndpointGuard.forbidden(Inet6Address.getByAddress("mapped", mapped, 0)));
        mapped[12] = (byte) 93;
        mapped[13] = (byte) 184;
        mapped[14] = (byte) 216;
        mapped[15] = 34;
        assertFalse(EndpointGuard.forbidden(Inet6Address.getByAddress("mapped", mapped, 0)));

        FakeDns dns = new FakeDns(Map.of("mapped.example", List.of("::ffff:127.0.0.1"), "mapped-meta.example", List.of("::ffff:a9fe:a9fe")));
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://mapped.example/"));
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://mapped-meta.example/"));
    }

    @Test
    void embeddedAndTunnelAddressesAreForbidden() throws UnknownHostException {
        for (String literal : List.of("::127.0.0.1", "::10.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1", "2002:0a00:1::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2",
                "2001:db8::1", "ff02::1", "fec0::1", "100::1", "::")) {
            assertTrue(EndpointGuard.forbidden(InetAddress.getByName(literal)), literal);
        }
        for (String literal : List.of("2606:4700:4700::1111", "2a00:1450:4001:81b::200e", "2002:5db8:d822::1")) {
            assertFalse(EndpointGuard.forbidden(InetAddress.getByName(literal)), literal);
        }
    }

    @Test
    void reservedIpv4RangesAreForbiddenAndNeighboursAreNot() throws UnknownHostException {
        for (String literal : List.of("0.1.2.3", "10.255.255.255", "100.127.255.255", "127.255.255.255", "169.254.0.1", "172.16.0.0", "172.31.255.255",
                "192.0.0.1", "192.0.2.5", "192.168.255.255", "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.255",
                "240.0.0.1", "255.255.255.255")) {
            assertTrue(EndpointGuard.forbidden(InetAddress.getByName(literal)), literal);
        }
        for (String literal : List.of("9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255", "128.0.0.0", "169.253.255.255",
                "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.167.255.255", "192.169.0.0", "198.17.255.255", "198.20.0.0", "223.255.255.255")) {
            assertFalse(EndpointGuard.forbidden(InetAddress.getByName(literal)), literal);
        }
    }

    @Test
    void ipLiteralsAreCheckedLikeNames() {
        FakeDns dns = new FakeDns(Map.of("127.0.0.1", List.of("127.0.0.1"), "93.184.216.34", List.of("93.184.216.34"), "::1", List.of("::1"),
                "10.0.0.1", List.of("10.0.0.1")));
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://127.0.0.1/"));
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://[::1]:8443/"));
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://10.0.0.1:443/"));
        assertEquals("93.184.216.34", guard.check("https://93.184.216.34/").host());
        // Скобки IPv6 в адресе остаются в адресе: у цели имя без них
        assertTrue(dns.asked.contains("::1"), dns.asked.toString());
    }

    @Test
    void oddIpNotationsAreMalformedAndNeverResolved() {
        FakeDns dns = new FakeDns(Map.of());
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        for (String url : List.of("https://2130706433/", "https://0x7f.0.0.1/", "https://0x7f000001/", "https://127.1/", "https://0177.0.0.1/",
                "https://017.0.0.1/", "https://1.2.3.4.5/", "https://256.1.1.1/", "https://127.0.0.1./", "https://a.1/",
                "https://127.0.0.1.:443x/", "https://exa mple.com/", "https:///path", "https://[::1/")) {
            assertEquals(Reason.MALFORMED, reasonOf(guard, url), url);
        }
        assertTrue(dns.asked.isEmpty(), dns.asked.toString());
    }

    @Test
    void onlyHttpsOnPort443Or8443() {
        FakeDns dns = new FakeDns(Map.of("seller.example", List.of("93.184.216.34")));
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        for (String url : List.of("http://seller.example/", "HTTP://seller.example/", "ftp://seller.example/", "file:///etc/passwd", "gopher://seller.example/",
                "ws://seller.example/", "wss://seller.example/")) {
            assertEquals(Reason.NOT_HTTPS, reasonOf(guard, url), url);
        }
        for (String url : List.of("//seller.example/", "seller.example/hook", "javascript:alert(1)", "mailto:a@b.c")) {
            assertTrue(Set.of(Reason.MALFORMED, Reason.NOT_HTTPS).contains(reasonOf(guard, url)), url);
        }
        for (String url : List.of("https://seller.example:80/", "https://seller.example:8080/", "https://seller.example:22/", "https://seller.example:6379/",
                "https://seller.example:65535/", "https://seller.example:0/", "https://seller.example:444/")) {
            assertEquals(Reason.PORT_NOT_ALLOWED, reasonOf(guard, url), url);
        }
        assertEquals(443, guard.check("https://seller.example:443/").port());
        assertEquals(443, guard.check("HTTPS://Seller.Example/").port());
        assertEquals("seller.example", guard.check("HTTPS://Seller.Example/").host());
        assertTrue(dns.asked.stream().allMatch("seller.example"::equals));
    }

    @Test
    void userInfoFragmentEmptyAndLongAddressesAreRejected() {
        FakeDns dns = new FakeDns(Map.of("seller.example", List.of("93.184.216.34"), "evil.example", List.of("93.184.216.35")));
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        assertEquals(Reason.USER_INFO, reasonOf(guard, "https://user:secret@seller.example/"));
        assertEquals(Reason.USER_INFO, reasonOf(guard, "https://seller.example@evil.example/"));
        assertEquals(Reason.USER_INFO, reasonOf(guard, "https://@seller.example/"));
        assertEquals(Reason.FRAGMENT, reasonOf(guard, "https://seller.example/hook#part"));
        assertEquals(Reason.EMPTY, reasonOf(guard, null));
        assertEquals(Reason.EMPTY, reasonOf(guard, "   "));
        String longPath = "https://seller.example/" + "a".repeat(EndpointGuard.MAX_LENGTH);
        assertEquals(Reason.TOO_LONG, reasonOf(guard, longPath));
        String limit = "https://seller.example/" + "a".repeat(EndpointGuard.MAX_LENGTH - "https://seller.example/".length());
        assertEquals(EndpointGuard.MAX_LENGTH, limit.length());
        assertEquals(limit.substring("https://seller.example".length()), guard.check(limit).target());
    }

    @Test
    void unresolvedNameIsRejected() {
        EndpointGuard guard = new EndpointGuard(new FakeDns(Map.of()), Set.of());
        assertEquals(Reason.UNRESOLVED, reasonOf(guard, "https://no-such-host.example/"));
        EndpointGuard empty = new EndpointGuard(host -> new InetAddress[0], Set.of());
        assertEquals(Reason.UNRESOLVED, reasonOf(empty, "https://seller.example/"));
        EndpointGuard broken = new EndpointGuard(host -> {
            throw new IllegalStateException("resolver is down");
        }, Set.of());
        assertEquals(Reason.UNRESOLVED, reasonOf(broken, "https://seller.example/"));
    }

    @Test
    void oneInternalAddressAmongPublicOnesRejectsTheName() {
        FakeDns dns = new FakeDns(Map.of("split.example", List.of("93.184.216.34", "10.0.0.7"), "split-first.example", List.of("10.0.0.7", "93.184.216.34")));
        EndpointGuard guard = new EndpointGuard(dns, Set.of());
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://split.example/"));
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://split-first.example/"));
    }

    @Test
    void dnsAnswerIsUsedOnceAndCallGoesToTheCheckedAddress() {
        // Подмена DNS между проверкой и соединением: при первом вопросе имя ведёт на публичный адрес, при втором на внутренний
        List<String> sequence = new ArrayList<>(List.of("93.184.216.34", "127.0.0.1"));
        int[] questions = {0};
        EndpointGuard guard = new EndpointGuard(host -> {
            questions[0]++;
            return new InetAddress[] {InetAddress.getByName(sequence.remove(0))};
        }, Set.of());
        EndpointGuard.Target target = guard.check("https://rebind.example/hook");
        assertEquals(1, questions[0]);
        assertEquals("93.184.216.34", target.address().getHostAddress());
        // Соединение открывается по target.address(): повторного вопроса к DNS нет, 127.0.0.1 до него не доходит
        assertEquals(1, questions[0]);
        // Следующий вызов проверяет имя заново и видит внутренний адрес
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://rebind.example/hook"));
        assertEquals(2, questions[0]);
    }

    @Test
    void allowListLiftsOnlyTheAddressRangeCheck() {
        FakeDns dns = new FakeDns(Map.of("seller-stub", List.of("10.20.30.40"), "other-stub", List.of("10.20.30.41")));
        EndpointGuard guard = new EndpointGuard(dns, Set.of("Seller-Stub", " ", "other-stub:8443"));
        assertEquals("10.20.30.40", guard.check("https://seller-stub:8443/seller/x/request").address().getHostAddress());
        assertEquals("10.20.30.40", guard.check("https://seller-stub/").address().getHostAddress());
        // Остальные правила действуют и для имени из списка
        assertEquals(Reason.NOT_HTTPS, reasonOf(guard, "http://seller-stub:8443/"));
        assertEquals(Reason.PORT_NOT_ALLOWED, reasonOf(guard, "https://seller-stub:9000/"));
        assertEquals(Reason.USER_INFO, reasonOf(guard, "https://u:p@seller-stub:8443/"));
        // Запись с портом действует только на этот порт
        assertEquals("10.20.30.41", guard.check("https://other-stub:8443/").address().getHostAddress());
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(guard, "https://other-stub/"));
    }

    @Test
    void emptyAllowListByDefaultMeansStubAddressIsRejected() {
        // Проверка из руководства: без списка адрес заглушки (внутренняя сеть стенда) отвергается
        FakeDns dns = new FakeDns(Map.of("external-stubs", List.of("172.18.0.9")));
        assertEquals(Reason.FORBIDDEN_ADDRESS, reasonOf(new EndpointGuard(dns, Set.of()), "https://external-stubs:8443/seller/demo/request"));
    }

    @Test
    void rejectionMessageNeverContainsTheAddress() {
        EndpointGuard guard = new EndpointGuard(new FakeDns(BADMAP()), Set.of());
        EndpointGuard.RejectedException e = assertThrows(EndpointGuard.RejectedException.class,
                () -> guard.check("https://user:topsecret@loopback.example/private-path?token=abc"));
        assertFalse(e.getMessage().contains("topsecret"), e.getMessage());
        assertFalse(e.getMessage().contains("loopback.example"), e.getMessage());
        assertFalse(e.getMessage().contains("token"), e.getMessage());
    }

    private static Map<String, List<String>> BADMAP() {
        return toLists(BAD);
    }

    private static Map<String, List<String>> toLists(Map<String, String> single) {
        Map<String, List<String>> out = new java.util.HashMap<>();
        single.forEach((k, v) -> out.put(k, List.of(v)));
        return out;
    }
}
