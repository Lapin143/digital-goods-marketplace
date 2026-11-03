package dgm.kit.client;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Защита от SSRF для адреса системы продавца (ADR-028, правило 2 руководства Ф5, угроза T-46). Платформа делает запрос по адресу,
 * который назвал продавец, поэтому адрес проверяется при сохранении и перед каждым вызовом (U21, Р-187).
 *
 * <p>Что отвергается: любая схема кроме {@code https}, порт не 443 и не 8443, адрес длиннее 300 знаков, имя пользователя и пароль в адресе,
 * фрагмент, имя, которое не разрешилось, и имя, у которого <b>хотя бы один</b> адрес внутренний или служебный (loopback, частные диапазоны,
 * 100.64/10, link-local с адресом метаданных облака, {@code 0.0.0.0}, групповые и зарезервированные, {@code ::1}, {@code fc00::/7},
 * {@code fe80::/10}, отображённые и встроенные IPv4). Имя разрешается здесь, а соединение идёт к уже проверенному {@link InetAddress}
 * ({@link Target#address()}): между проверкой и вызовом DNS уже не спрашивается, подмена ответа («DNS rebinding») не срабатывает.
 *
 * <p>Список разрешённых имён ({@code dgm.seller-endpoint.allow}) пуст по умолчанию. Его задаёт только стендовый профиль, где заглушка
 * продавца живёт во внутренней сети (Р-179): имя из списка освобождается от проверки диапазонов, но не от {@code https}, порта и
 * остальных правил. Элемент списка это {@code имя} или {@code имя:порт} без учёта регистра.
 */
public final class EndpointGuard {

    /** Самый длинный допустимый адрес. */
    public static final int MAX_LENGTH = 300;
    /** Порты, на которые платформа ходит к системе продавца. */
    public static final Set<Integer> ALLOWED_PORTS = Set.of(443, 8443);

    /** Причина отказа: по ней метрика {@code endpoint-not-allowed} различает классы ошибок, пользователю показывается общий текст. */
    public enum Reason {
        EMPTY, TOO_LONG, MALFORMED, NOT_HTTPS, USER_INFO, FRAGMENT, PORT_NOT_ALLOWED, UNRESOLVED, FORBIDDEN_ADDRESS
    }

    /** Адрес не прошёл проверку. Сообщение не содержит адреса продавца: оно может попасть в журнал. */
    public static final class RejectedException extends RuntimeException {
        private final Reason reason;

        public RejectedException(Reason reason) {
            super("Адрес системы продавца не разрешён: " + reason);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Разрешение имени в адреса. В рабочем коде это системный DNS, в тестах подставной ответ. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * Проверенная цель вызова.
     *
     * @param host   имя из адреса (для SNI и проверки имени в сертификате продавца); у адреса IPv6 без скобок
     * @param port   порт
     * @param address проверенный адрес, к которому идёт соединение
     * @param target строка запроса: путь и параметры, не пустая
     */
    public record Target(String host, int port, InetAddress address, String target) {
    }

    private final Resolver resolver;
    private final Set<String> allow;

    public EndpointGuard(Resolver resolver, Set<String> allow) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        Set<String> normalized = new HashSet<>();
        for (String entry : allow) {
            if (!entry.isBlank()) {
                normalized.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.allow = Set.copyOf(normalized);
    }

    /** Защита с системным DNS. */
    public static EndpointGuard system(Set<String> allow) {
        return new EndpointGuard(InetAddress::getAllByName, allow);
    }

    /** Проверка адреса: возвращает цель вызова или бросает {@link RejectedException}. Сетевых соединений не открывает. */
    public Target check(String url) {
        if (url == null || url.isBlank()) {
            throw new RejectedException(Reason.EMPTY);
        }
        if (url.length() > MAX_LENGTH) {
            throw new RejectedException(Reason.TOO_LONG);
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (java.net.URISyntaxException e) {
            throw new RejectedException(Reason.MALFORMED);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new RejectedException(uri.getScheme() == null ? Reason.MALFORMED : Reason.NOT_HTTPS);
        }
        if (uri.getRawUserInfo() != null) {
            throw new RejectedException(Reason.USER_INFO);
        }
        if (uri.getRawFragment() != null) {
            throw new RejectedException(Reason.FRAGMENT);
        }
        // Разбор URI непоследователен: «0x7f.0.0.1» и «127.1» он отвергает, а «2130706433», «0x7f000001» и «0177.0.0.1» принимает как имя
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RejectedException(Reason.MALFORMED);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            // IPv6-адрес в адресе пишется в скобках; для разрешения имени, SNI и проверки сертификата скобки не нужны
            host = host.substring(1, host.length() - 1);
        } else if (numericLooking(host) && !CANONICAL_IPV4.matcher(host).matches()) {
            // Последняя часть имени из цифр или 0x…: так пишут числовые адреса в десятичной, шестнадцатеричной и восьмеричной записи, которые
            // системный DNS разворачивает в IPv4. Допускается только обычная запись из четырёх чисел, у настоящих имён такой последней части нет
            throw new RejectedException(Reason.MALFORMED);
        }
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        if (!ALLOWED_PORTS.contains(port)) {
            throw new RejectedException(Reason.PORT_NOT_ALLOWED);
        }
        InetAddress[] resolved;
        try {
            resolved = resolver.resolve(host);
        } catch (UnknownHostException | RuntimeException e) {
            throw new RejectedException(Reason.UNRESOLVED);
        }
        if (resolved == null || resolved.length == 0) {
            throw new RejectedException(Reason.UNRESOLVED);
        }
        boolean listed = allow.contains(host) || allow.contains(host + ":" + port);
        if (!listed) {
            for (InetAddress address : resolved) {
                if (forbidden(address)) {
                    throw new RejectedException(Reason.FORBIDDEN_ADDRESS);
                }
            }
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return new Target(host, port, resolved[0], path + query);
    }

    /** Адрес внутренний или служебный: на него платформа не ходит. */
    public static boolean forbidden(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        return bytes.length == 4 ? forbiddenV4(bytes) : forbiddenV6(bytes);
    }

    private static final java.util.regex.Pattern CANONICAL_IPV4 =
            java.util.regex.Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

    private static boolean numericLooking(String host) {
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String last = name.substring(name.lastIndexOf('.') + 1);
        return !last.isEmpty() && (last.chars().allMatch(c -> c >= '0' && c <= '9') || (last.startsWith("0x") && last.length() > 2));
    }

    // Диапазоны IPv4: адрес, длина префикса
    private static final int[][] V4 = {
        {addr(0, 0, 0, 0), 8},         // «эта сеть», в том числе 0.0.0.0
        {addr(10, 0, 0, 0), 8},        // частная сеть
        {addr(100, 64, 0, 0), 10},     // общий адрес операторов (CGNAT)
        {addr(127, 0, 0, 0), 8},       // loopback
        {addr(169, 254, 0, 0), 16},    // link-local, адрес метаданных облака 169.254.169.254
        {addr(172, 16, 0, 0), 12},     // частная сеть
        {addr(192, 0, 0, 0), 24},      // служебные назначения IETF
        {addr(192, 0, 2, 0), 24},      // документация
        {addr(192, 88, 99, 0), 24},    // 6to4-ретрансляция
        {addr(192, 168, 0, 0), 16},    // частная сеть
        {addr(198, 18, 0, 0), 15},     // стенды измерений
        {addr(198, 51, 100, 0), 24},   // документация
        {addr(203, 0, 113, 0), 24},    // документация
        {addr(224, 0, 0, 0), 4},       // групповая рассылка
        {addr(240, 0, 0, 0), 4},       // зарезервировано, в том числе 255.255.255.255
    };

    private static int addr(int a, int b, int c, int d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    private static boolean forbiddenV4(byte[] b) {
        int value = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        for (int[] range : V4) {
            int mask = range[1] == 0 ? 0 : -1 << (32 - range[1]);
            if ((value & mask) == (range[0] & mask)) {
                return true;
            }
        }
        return false;
    }

    private static boolean forbiddenV6(byte[] b) {
        boolean zeroPrefix80 = isZero(b, 0, 10);
        // ::, ::1 и IPv4-совместимые ::a.b.c.d (первые 96 бит нулевые): адрес IPv4 в хвосте проверяется как обычный
        if (zeroPrefix80 && b[10] == 0 && b[11] == 0) {
            return forbiddenV4(Arrays.copyOfRange(b, 12, 16));
        }
        // ::ffff:a.b.c.d (отображённый IPv4)
        if (zeroPrefix80 && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            return forbiddenV4(Arrays.copyOfRange(b, 12, 16));
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        // fc00::/7 (уникальные локальные), fe00::/8 (link-local fe80::/10 и устаревшие локальные fec0::/10), ff00::/8 (групповые)
        if ((first & 0xFE) == 0xFC || first == 0xFE || first == 0xFF) {
            return true;
        }
        // 64:ff9b::/96 (NAT64) и 64:ff9b:1::/48: за ними стоит произвольный IPv4
        if (first == 0x00 && second == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) {
            return true;
        }
        // 100::/64 (адреса отбрасывания)
        if (first == 0x01 && second == 0x00 && isZero(b, 2, 8)) {
            return true;
        }
        // 2001::/32 (Teredo) и 2001:db8::/32 (документация)
        if (first == 0x20 && second == 0x01 && ((b[2] == 0x00 && b[3] == 0x00) || ((b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8))) {
            return true;
        }
        // 2002::/16 (6to4): IPv4 в байтах 2–5
        if (first == 0x20 && second == 0x02) {
            return forbiddenV4(Arrays.copyOfRange(b, 2, 6));
        }
        return false;
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
