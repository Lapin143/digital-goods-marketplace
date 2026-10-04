package dgm.kit.route;

import dgm.kit.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Правила маршрутов сервиса. Файл создаёт tools/docs-checks/gen_routes.py из OpenAPI, поэтому областей, ролей и списков вызывающих
 * в коде сервиса нет: контракт и защита не расходятся (roles-permissions.md, раздел 10).
 *
 * <p>Формат JSON: {@code {"service": "...", "routes": [{"method", "path", "operationId", "scopes", "roles", "smsSession", "callers"}]}}.
 */
public final class RoutePolicy {

    private record Compiled(RouteRule rule, Pattern pattern) {
    }

    private final String service;
    private final List<Compiled> routes;

    private RoutePolicy(String service, List<Compiled> routes) {
        this.service = service;
        this.routes = routes;
    }

    public static RoutePolicy fromJson(String json) {
        Map<String, Object> doc = Json.parseObject(json);
        List<Compiled> compiled = new ArrayList<>();
        Object routes = doc.get("routes");
        if (!(routes instanceof List<?> list)) {
            throw new IllegalArgumentException("В правилах маршрутов нет списка routes");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("Маршрут должен быть объектом");
            }
            RouteRule rule = new RouteRule(
                    text(raw, "method").toUpperCase(Locale.ROOT),
                    text(raw, "path"),
                    text(raw, "operationId"),
                    strings(raw, "scopes"),
                    strings(raw, "roles"),
                    "denied".equals(raw.get("smsSession")),
                    strings(raw, "callers"));
            compiled.add(new Compiled(rule, rule.pattern()));
        }
        // Точные шаблоны раньше общих: /orders/current раньше /orders/{id}
        compiled.sort(Comparator.comparingInt((Compiled c) -> c.rule().literalSegments()).reversed());
        return new RoutePolicy(String.valueOf(doc.getOrDefault("service", "")), List.copyOf(compiled));
    }

    public static RoutePolicy fromClasspath(String resource) {
        try (InputStream in = RoutePolicy.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Нет файла правил маршрутов " + resource);
            }
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Файл правил маршрутов не читается: " + resource, e);
        }
    }

    public String service() {
        return service;
    }

    public List<RouteRule> rules() {
        return routes.stream().map(Compiled::rule).toList();
    }

    /** Правило маршрута для метода и пути запроса (без строки запроса). */
    public Optional<RouteRule> match(String method, String path) {
        String m = method.toUpperCase(Locale.ROOT);
        String p = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (Compiled c : routes) {
            if (c.rule().method().equals(m) && c.pattern().matcher(p).matches()) {
                return Optional.of(c.rule());
            }
        }
        return Optional.empty();
    }

    private static String text(Map<?, ?> raw, String key) {
        Object v = raw.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("У маршрута нет поля " + key);
        }
        return s;
    }

    private static List<String> strings(Map<?, ?> raw, String key) {
        Object v = raw.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new IllegalArgumentException("Поле " + key + " должно быть списком");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(String.valueOf(o));
        }
        return out;
    }
}
