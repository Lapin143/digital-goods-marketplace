package dgm.gateway;

import dgm.kit.json.Json;
import dgm.kit.route.RoutePolicy;
import dgm.kit.route.RouteRule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Таблица маршрутов шлюза (ADR-021). Внешние маршруты API берутся из {@code dgm/gateway-routes.json}: файл создаёт
 * tools/docs-checks/gen_routes.py из OpenAPI всех сервисов, поэтому шлюз и сервисы читают один контракт. Всё, что не описано в OpenAPI
 * и не входит в список ниже, закрыто (ответ 404). Внутренние маршруты {@code /internal/**} и служебные {@code /actuator/**} снаружи
 * недоступны никогда: метрики и здоровье шлюз отдаёт только на порту управления.
 *
 * <p>Маршруты вне OpenAPI: вход и токены Keycloak ({@code /auth}), документы продавцов из хранилища ({@code /files}), заглушки внешних
 * систем стенда ({@code /vkid}, {@code /payment/pay}: только если адрес заглушки задан) и веб-интерфейс (всё остальное, только чтение).
 */
public final class RouteTable {

    /** Как маршрут защищён: открыт, подпись проверяет сервис (вебхук) или нужен токен. */
    public enum Kind {
        PUBLIC, WEBHOOK, TOKEN
    }

    /**
     * Куда идёт запрос.
     *
     * @param service сервис или система-получатель (ключ {@code dgm.gateway.upstreams})
     * @param kind    способ защиты
     * @param limit   группа лимита частоты
     * @param rule    правило маршрута из OpenAPI, у маршрутов вне OpenAPI {@code null}
     */
    public record Target(String service, Kind kind, String limit, RouteRule rule) {

        public boolean requiresToken() {
            return kind == Kind.TOKEN;
        }
    }

    private record Extra(String service, Kind kind, String limit) {
    }

    private record Prefix(String path, String service, String limit, Set<String> methods) {

        boolean matches(String candidate) {
            return candidate.equals(path) || candidate.startsWith(path + "/");
        }
    }

    private static final List<Prefix> PREFIXES = List.of(
            new Prefix("/auth", "keycloak", "auth", Set.of("GET", "HEAD", "POST")),
            new Prefix("/files", "object-storage", "files", Set.of("GET", "HEAD")),
            new Prefix("/vkid", "external-stubs", "auth", Set.of("GET")),
            new Prefix("/payment/pay", "external-stubs", "web", Set.of("GET", "POST")));

    private static final Set<String> WEB_METHODS = Set.of("GET", "HEAD");

    private final RoutePolicy policy;
    private final Map<String, Extra> byOperation;
    private final Set<String> upstreams;

    private RouteTable(RoutePolicy policy, Map<String, Extra> byOperation, Set<String> upstreams) {
        this.policy = policy;
        this.byOperation = byOperation;
        this.upstreams = Set.copyOf(upstreams);
    }

    public static RouteTable fromClasspath(String resource, Set<String> upstreams) {
        try (InputStream in = RouteTable.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Нет таблицы маршрутов шлюза " + resource);
            }
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), upstreams);
        } catch (IOException e) {
            throw new UncheckedIOException("Таблица маршрутов шлюза не читается: " + resource, e);
        }
    }

    public static RouteTable fromJson(String json, Set<String> upstreams) {
        RoutePolicy policy = RoutePolicy.fromJson(json);
        Map<String, Extra> extras = new HashMap<>();
        Object routes = Json.parseObject(json).get("routes");
        if (!(routes instanceof List<?> list)) {
            throw new IllegalArgumentException("В таблице маршрутов нет списка routes");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Маршрут должен быть объектом");
            }
            String operation = String.valueOf(raw.get("operationId"));
            Kind kind = Kind.valueOf(String.valueOf(raw.get("auth")).toUpperCase(Locale.ROOT));
            extras.put(operation, new Extra(String.valueOf(raw.get("service")), kind, String.valueOf(raw.get("limit"))));
        }
        return new RouteTable(policy, Map.copyOf(extras), upstreams);
    }

    /** Маршруты из OpenAPI и их число (для проверки полноты таблицы). */
    public List<RouteRule> rules() {
        return policy.rules();
    }

    /** Группа лимита и сервис маршрута из таблицы по идентификатору операции. */
    public Optional<Target> target(String operationId) {
        Extra extra = byOperation.get(operationId);
        if (extra == null) {
            return Optional.empty();
        }
        return policy.rules().stream().filter(r -> r.operationId().equals(operationId)).findFirst()
                .map(rule -> new Target(extra.service(), extra.kind(), extra.limit(), rule));
    }

    /** Куда направить запрос; пусто, если маршрута нет и запрос закрывается ответом 404. Путь без строки запроса. */
    public Optional<Target> resolve(String method, String rawPath) {
        String verb = method.toUpperCase(Locale.ROOT);
        String path = rawPath.length() > 1 && rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
        if (isUnder(path, "/internal") || isUnder(path, "/actuator")) {
            return Optional.empty();
        }
        if (isUnder(path, "/api")) {
            return policy.match(verb, path).map(rule -> {
                Extra extra = byOperation.get(rule.operationId());
                return new Target(extra.service(), extra.kind(), extra.limit(), rule);
            });
        }
        for (Prefix prefix : PREFIXES) {
            if (prefix.matches(path)) {
                return upstreams.contains(prefix.service()) && prefix.methods().contains(verb)
                        ? Optional.of(new Target(prefix.service(), Kind.PUBLIC, prefix.limit(), null))
                        : Optional.empty();
            }
        }
        if (!upstreams.contains("web-app") || !WEB_METHODS.contains(verb)) {
            return Optional.empty();
        }
        return Optional.of(new Target("web-app", Kind.PUBLIC, "web", null));
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
