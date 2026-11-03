package dgm.kit.security;

import dgm.kit.route.RouteRule;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * Личность по API-ключу, переданная шлюзом (ADR-027, решение 4; Р-186). После проверки ключа шлюз добавляет к запросу заголовки
 * {@code X-Seller-Id} и {@code X-Auth-Kind: api-key} и передаёт его сервису по mTLS.
 *
 * <p>Заголовки принимаются только при двух условиях сразу: CN клиентского сертификата равен {@code api-gateway} и маршрут помечен
 * {@code x-seller-api}. Иначе они не значат ничего: вызов сервиса напрямую, чужой сертификат и маршрут без ключа читают запрос так,
 * будто заголовков нет. Подделка заголовка в таком запросе не даёт личности: нужен токен, а без него ответ 401. Шлюз сам удаляет эти заголовки
 * из внешнего запроса до разбора, это вторая линия защиты (T-45).
 */
public final class GatewayIdentity {

    /** Имя шлюза в сертификате: только этому вызывающему сервис верит. */
    public static final String GATEWAY_CALLER = "api-gateway";
    public static final String SELLER_ID_HEADER = "X-Seller-Id";
    public static final String AUTH_KIND_HEADER = "X-Auth-Kind";
    public static final String AUTH_KIND_API_KEY = "api-key";

    /** Исход разбора заголовков. */
    public enum Status {
        /** Заголовки не приняты в расчёт: их нет или принять их нельзя (не шлюз, не маршрут ключа). */
        ABSENT,
        /** Шлюз передал личность по ключу, идентификатор разобран. */
        TRUSTED,
        /** Шлюз передал заголовки, но они не складываются в личность: нет второго заголовка, вид входа другой, идентификатор не UUID. */
        MALFORMED
    }

    /** Результат разбора: статус и, для {@link Status#TRUSTED}, идентификатор профиля продавца. */
    public record Result(Status status, String sellerId) {

        static final Result ABSENT = new Result(Status.ABSENT, null);
        static final Result MALFORMED = new Result(Status.MALFORMED, null);

        public boolean trusted() {
            return status == Status.TRUSTED;
        }
    }

    private GatewayIdentity() {
    }

    public static Result resolve(HttpServletRequest request, RouteRule rule) {
        if (!rule.sellerApi()) {
            return Result.ABSENT;
        }
        String caller = CallerFilter.callerName(request.getAttribute(CallerFilter.CERTIFICATE_ATTRIBUTE)).orElse(null);
        if (!GATEWAY_CALLER.equals(caller)) {
            return Result.ABSENT;
        }
        String sellerId = request.getHeader(SELLER_ID_HEADER);
        String kind = request.getHeader(AUTH_KIND_HEADER);
        if (sellerId == null && kind == null) {
            return Result.ABSENT;
        }
        if (sellerId == null || !AUTH_KIND_API_KEY.equals(kind)) {
            return Result.MALFORMED;
        }
        try {
            // Канонический вид: UUID разбирается слабее, чем пишется (Java принимает короткие группы), а в журнал и в базу идёт строка
            UUID parsed = UUID.fromString(sellerId.trim());
            if (!parsed.toString().equals(sellerId.trim().toLowerCase(java.util.Locale.ROOT))) {
                return Result.MALFORMED;
            }
            return new Result(Status.TRUSTED, parsed.toString());
        } catch (IllegalArgumentException e) {
            return Result.MALFORMED;
        }
    }
}
