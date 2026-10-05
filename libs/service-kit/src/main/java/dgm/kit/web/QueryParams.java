package dgm.kit.web;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.kit.time.Timestamps;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Разбор и проверка параметров запроса списка (conventions.md, раздел 10). Ошибки копятся и выдаются одним ответом 422
 * {@code validation-failed} с полем {@code errors}: {@code [{parameter, code, detail}]}. Неизвестный параметр тоже ошибка (10.5),
 * параметры {@code limit} и {@code cursor} известны всегда.
 */
public final class QueryParams {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_CURSOR_LENGTH = 512;

    private static final Pattern WORDS = Pattern.compile("^[a-z_]+$");
    private static final Pattern INTEGER = Pattern.compile("^[0-9]{1,18}$");

    private final HttpServletRequest request;
    private final List<Map<String, Object>> errors = new ArrayList<>();

    private QueryParams(HttpServletRequest request) {
        this.request = request;
    }

    /** @param known параметры операции, кроме {@code limit} и {@code cursor} */
    public static QueryParams of(HttpServletRequest request, String... known) {
        QueryParams params = new QueryParams(request);
        Set<String> allowed = new HashSet<>(List.of(known));
        allowed.add("limit");
        allowed.add("cursor");
        for (String name : new TreeSet<>(request.getParameterMap().keySet())) {
            if (!allowed.contains(name)) {
                params.error(name, "unknown_value", "Неизвестный параметр запроса.");
            }
        }
        return params;
    }

    public void error(String parameter, String code, String detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("parameter", parameter);
        e.put("code", code);
        e.put("detail", detail);
        errors.add(e);
    }

    /** Значение параметра; параметр, заданный несколько раз, это ошибка. */
    private Optional<String> single(String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return Optional.empty();
        }
        if (values.length > 1) {
            error(name, "invalid_format", "Параметр задан несколько раз.");
            return Optional.empty();
        }
        return Optional.of(values[0]);
    }

    /** Размер страницы: по умолчанию 20, от 1 до 100. */
    public int limit() {
        OptionalLong value = integer("limit", 1, MAX_LIMIT);
        return value.isPresent() ? (int) value.getAsLong() : DEFAULT_LIMIT;
    }

    /** Курсор как есть (длина не больше 512 знаков). */
    public Optional<String> cursor() {
        return text("cursor", 1, MAX_CURSOR_LENGTH);
    }

    /**
     * Содержимое курсора с обязательными ключами. Курсор не из нашего ответа: ошибка параметра {@code cursor}.
     */
    public Optional<Map<String, String>> cursorValues(String... keys) {
        Optional<String> raw = cursor();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, String> values = PageCursor.decode(raw.get());
            for (String key : keys) {
                if (values.get(key) == null) {
                    throw new IllegalArgumentException("В курсоре нет " + key);
                }
            }
            return Optional.of(values);
        } catch (IllegalArgumentException e) {
            error("cursor", "invalid_format", "Курсор не разобран: возьмите его из page.nextCursor предыдущего ответа.");
            return Optional.empty();
        }
    }

    public Optional<String> text(String name, int min, int max) {
        Optional<String> value = single(name);
        if (value.isEmpty()) {
            return value;
        }
        int length = value.get().length();
        if (length < min) {
            error(name, "too_short", "Значение короче " + min + " зн.");
            return Optional.empty();
        }
        if (length > max) {
            error(name, "too_long", "Значение длиннее " + max + " зн.");
            return Optional.empty();
        }
        return value;
    }

    /** Строка по образцу, например код страны. */
    public Optional<String> matching(String name, Pattern pattern, int max) {
        Optional<String> value = text(name, 1, max);
        if (value.isPresent() && !pattern.matcher(value.get()).matches()) {
            error(name, "invalid_format", "Значение не соответствует формату.");
            return Optional.empty();
        }
        return value;
    }

    /** Целое число из диапазона. */
    public OptionalLong integer(String name, long min, long max) {
        Optional<String> value = single(name);
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        if (!INTEGER.matcher(value.get()).matches()) {
            error(name, "invalid_format", "Нужно целое неотрицательное число.");
            return OptionalLong.empty();
        }
        long number = Long.parseLong(value.get());
        if (number < min || number > max) {
            error(name, "out_of_range", "Допустимо от " + min + " до " + max + ".");
            return OptionalLong.empty();
        }
        return OptionalLong.of(number);
    }

    /** Момент времени RFC 3339. */
    public Optional<Instant> instant(String name) {
        Optional<String> value = single(name);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Optional<Instant> parsed = Timestamps.parse(value.get());
        if (parsed.isEmpty()) {
            error(name, "invalid_format", "Нужен момент времени по RFC 3339, например 2026-10-03T12:00:00Z.");
        }
        return parsed;
    }

    /** Несколько значений через запятую из закрытого перечня; повторы убираются, порядок сохраняется. */
    public Optional<List<String>> list(String name, Set<String> allowed) {
        Optional<String> value = single(name);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Set<String> items = new LinkedHashSet<>();
        for (String item : value.get().split(",", -1)) {
            if (!WORDS.matcher(item).matches()) {
                error(name, "invalid_format", "Значения перечисляются через запятую строчными латинскими буквами.");
                return Optional.empty();
            }
            if (!allowed.contains(item)) {
                error(name, "unknown_value", "Неизвестное значение «" + item + "».");
                return Optional.empty();
            }
            items.add(item);
        }
        return Optional.of(List.copyOf(items));
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    /** Если есть ошибки, бросает 422 {@code validation-failed} со списком {@code errors}. */
    public void check() {
        if (!errors.isEmpty()) {
            throw new ProblemException(ProblemType.VALIDATION_FAILED, "Данные не прошли проверку, подробности в поле errors.",
                    Map.of("errors", List.copyOf(errors)), Map.of());
        }
    }
}
