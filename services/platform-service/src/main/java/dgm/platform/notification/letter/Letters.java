package dgm.platform.notification.letter;

import dgm.platform.notification.api.InvalidNotificationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Шаблоны писем (часть {@code notification-service}): русский текст из {@code templates/<код>.txt}, подстановка по именованным полям
 * {@code {поле}}. Файл состоит из строки {@code Тема: ...}, строки {@code ---} и текста. Поле {@code {name}} это имя получателя, остальные
 * поля шаблона объявлены ниже вместе с видом значения.
 *
 * <p>Шаблон загружается и проверяется при старте: неизвестное поле в тексте или объявленное, но не использованное, останавливает запуск,
 * а не первое письмо. Параметры проверяются при постановке в очередь и при отправке: лишний ключ, пропущенный ключ и значение неверного
 * вида отклоняются. Значения подставляются за один проход и повторно не разбираются, поэтому текст модератора с фигурными скобками
 * ничего не подставит. Секретов в письмах среза 1 нет; пакет {@code letter} единственный из прикладных, где разрешено читать значение
 * секрета, когда письма с ключами появятся в срезе 3.
 */
public final class Letters {

    /** Вид значения параметра. */
    enum Kind {
        /** Текст до 1000 символов (причина или комментарий модератора). */
        TEXT,
        /** Момент времени в формате RFC 3339, в письме выводится в часовом поясе платформы. */
        TIME,
        /** Тип профиля продавца из события {@code seller.approved}. */
        SELLER_TYPE
    }

    /** Предел текста модератора, как в схеме события (AsyncAPI: maxLength 1000). */
    public static final int MAX_TEXT = 1000;

    private static final Map<String, Map<String, Kind>> FIELDS = Map.of(
            "seller-approved", Map.of("sellerType", Kind.SELLER_TYPE),
            "seller-rejected", Map.of("reason", Kind.TEXT, "nextApplicationAt", Kind.TIME),
            "seller-returned", Map.of("comment", Kind.TEXT),
            "user-sms-login", Map.of("loggedInAt", Kind.TIME));

    private static final Map<String, String> SELLER_TYPES = Map.of(
            "sole_proprietor", "индивидуальный предприниматель",
            "self_employed", "самозанятый",
            "legal_entity", "юридическое лицо");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final String SUBJECT_PREFIX = "Тема: ";
    private static final String SEPARATOR = "\n---\n";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"));
    private static final String FALLBACK_NAME = "пользователь";

    private record Template(String subject, String body) {
    }

    private final Map<String, Template> templates = new LinkedHashMap<>();
    private final ZoneId zone;
    private final String zoneLabel;

    /**
     * @param zone      часовой пояс времени в письмах
     * @param zoneLabel подпись пояса после времени, например «МСК»
     * @throws IllegalStateException шаблон не найден или не проходит проверку
     */
    public Letters(ZoneId zone, String zoneLabel) {
        this.zone = zone;
        this.zoneLabel = zoneLabel;
        for (Map.Entry<String, Map<String, Kind>> entry : FIELDS.entrySet()) {
            templates.put(entry.getKey(), load(entry.getKey(), entry.getValue().keySet()));
        }
    }

    /** Коды шаблонов этого набора. */
    public Set<String> codes() {
        return new TreeSet<>(templates.keySet());
    }

    /**
     * Проверяет, что шаблон есть, а параметры ему подходят.
     *
     * @throws InvalidNotificationException шаблона нет, ключи не совпадают с объявленными или значение неверного вида
     */
    public void validate(String template, Map<String, Object> params) {
        resolve(template, params);
    }

    /**
     * Собирает письмо.
     *
     * @param recipientName имя получателя для обращения
     * @throws InvalidNotificationException параметры не подходят шаблону
     */
    public Letter render(String template, String recipientName, Map<String, Object> params) {
        Map<String, String> values = new LinkedHashMap<>(resolve(template, params));
        values.put("name", recipientName == null || recipientName.isBlank() ? FALLBACK_NAME : recipientName.strip());
        Template parsed = templates.get(template);
        Matcher matcher = PLACEHOLDER.matcher(parsed.body());
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(text, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(text);
        return new Letter(parsed.subject(), text.toString());
    }

    private Map<String, String> resolve(String template, Map<String, Object> params) {
        Map<String, Kind> fields = FIELDS.get(template);
        if (fields == null) {
            throw new InvalidNotificationException("Нет шаблона письма " + template);
        }
        if (!params.keySet().equals(fields.keySet())) {
            Set<String> extra = new TreeSet<>(params.keySet());
            extra.removeAll(fields.keySet());
            Set<String> missing = new TreeSet<>(fields.keySet());
            missing.removeAll(params.keySet());
            throw new InvalidNotificationException("Параметры шаблона " + template + " не совпадают с объявленными: лишние " + extra
                    + ", не хватает " + missing);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, Kind> field : fields.entrySet()) {
            values.put(field.getKey(), format(template, field.getKey(), field.getValue(), params.get(field.getKey())));
        }
        return values;
    }

    private String format(String template, String key, Kind kind, Object value) {
        String where = "Параметр " + key + " шаблона " + template;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidNotificationException(where + " должен быть непустой строкой");
        }
        return switch (kind) {
            case TEXT -> text(where, text);
            case TIME -> time(where, text);
            case SELLER_TYPE -> sellerType(where, text);
        };
    }

    private static String text(String where, String value) {
        if (value.length() > MAX_TEXT) {
            throw new InvalidNotificationException(where + " длиннее " + MAX_TEXT + " символов");
        }
        return value.strip();
    }

    private String time(String where, String value) {
        try {
            return TIME.format(OffsetDateTime.parse(value).atZoneSameInstant(zone)) + " (" + zoneLabel + ")";
        } catch (DateTimeParseException e) {
            throw new InvalidNotificationException(where + " не разбирается как время RFC 3339");
        }
    }

    private static String sellerType(String where, String value) {
        String label = SELLER_TYPES.get(value);
        if (label == null) {
            throw new InvalidNotificationException(where + " не из списка типов профиля");
        }
        return label;
    }

    private static Template load(String code, Set<String> fields) {
        String resource = "templates/" + code + ".txt";
        String content;
        try (InputStream in = Letters.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Нет файла шаблона " + resource);
            }
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Файл шаблона " + resource + " не прочитан", e);
        }
        int separator = content.indexOf(SEPARATOR);
        if (!content.startsWith(SUBJECT_PREFIX) || separator < 0) {
            throw new IllegalStateException("Шаблон " + code + ": нужна строка «Тема: ...», затем строка «---» и текст");
        }
        String subject = content.substring(SUBJECT_PREFIX.length(), separator).strip();
        if (subject.isEmpty() || subject.contains("\n") || PLACEHOLDER.matcher(subject).find()) {
            throw new IllegalStateException("Шаблон " + code + ": тема в одну строку и без полей");
        }
        String body = content.substring(separator + SEPARATOR.length()).stripTrailing() + "\n";
        Set<String> used = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        Set<String> unknown = new TreeSet<>(used);
        unknown.remove("name");
        unknown.removeAll(fields);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Шаблон " + code + ": в тексте неизвестные поля " + unknown);
        }
        Set<String> unused = new TreeSet<>(fields);
        unused.removeAll(used);
        if (!unused.isEmpty() || !used.contains("name")) {
            throw new IllegalStateException("Шаблон " + code + ": в тексте нет поля name или объявленных полей " + unused);
        }
        return new Template(subject, body);
    }
}
