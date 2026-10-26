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
        SELLER_TYPE,
        /** Целое число без знака до 12 цифр: номер заказа, число попыток (Р-153). */
        NUMBER,
        /** Сумма в минимальных единицах валюты и код валюты через пробел, например {@code 149900 RUB}; в письме «1 499,00 ₽». */
        MONEY,
        /** Идентификатор в виде UUID. */
        ID,
        /** Причина возврата из события {@code order.refunded}, в письме готовая фраза. */
        REFUND_REASON,
        /** Причина неудачи выдачи из события {@code delivery.failed}, в письме словами. */
        FAILURE_REASON,
        /** Число секунд, в письме минуты: порог контроля доставки из события {@code delivery.overdue}. */
        SECONDS_AS_MINUTES,
        /** Одноразовый код из шести цифр: только письма, которые уходят напрямую (SEQ-04, шаг 13); в очередь такой шаблон не ставится. */
        CODE
    }

    /** Предел текста модератора, как в схеме события (AsyncAPI: maxLength 1000). */
    public static final int MAX_TEXT = 1000;

    /** Адрес сайта на стенде (Compose: {@code DGM_PUBLIC_ORIGIN}). */
    public static final String DEFAULT_SITE_URL = "https://localhost:8443";

    private static final Map<String, Map<String, Kind>> FIELDS = Map.ofEntries(
            Map.entry("seller-approved", Map.of("sellerType", Kind.SELLER_TYPE)),
            Map.entry("seller-rejected", Map.of("reason", Kind.TEXT, "nextApplicationAt", Kind.TIME)),
            Map.entry("seller-returned", Map.of("comment", Kind.TEXT)),
            Map.entry("user-sms-login", Map.of("loggedInAt", Kind.TIME)),
            // срез 2 (Р-138, Р-153): три письма покупателю и три администратору
            Map.entry("order-created", Map.of("orderNumber", Kind.NUMBER, "total", Kind.MONEY, "orderId", Kind.ID, "payUntil", Kind.TIME)),
            Map.entry("order-paid", Map.of("orderNumber", Kind.NUMBER, "productTitle", Kind.TEXT, "total", Kind.MONEY)),
            Map.entry("order-refunded", Map.of("orderNumber", Kind.NUMBER, "total", Kind.MONEY, "refundReason", Kind.REFUND_REASON)),
            Map.entry("payment-refund-escalated", Map.of("orderId", Kind.ID, "amount", Kind.MONEY, "attempts", Kind.NUMBER)),
            Map.entry("delivery-failed", Map.of("orderId", Kind.ID, "reason", Kind.FAILURE_REASON, "attempts", Kind.NUMBER)),
            Map.entry("delivery-overdue", Map.of("orderId", Kind.ID, "paidAt", Kind.TIME, "threshold", Kind.SECONDS_AS_MINUTES)),
            // срез 3 (Р-169): код смены e-mail на новый адрес, уходит напрямую, минуя очередь
            Map.entry("email-change-code", Map.of("code", Kind.CODE)));

    /** Шаблоны с одноразовым кодом: код в базу не попадает (правило 17), поэтому такое письмо уходит напрямую, а в очередь не ставится. */
    private static final Set<String> DIRECT_ONLY = Set.of("email-change-code");

    private static final Map<String, String> SELLER_TYPES = Map.of(
            "sole_proprietor", "индивидуальный предприниматель",
            "self_employed", "самозанятый",
            "legal_entity", "юридическое лицо");

    private static final Map<String, String> REFUND_REASONS = Map.of(
            "late_payment", "Причина: оплата пришла после того, как срок заказа истёк и товар уже нельзя было закрепить за вами.",
            "seller_api_timeout", "Причина: система продавца не ответила вовремя, и мы не смогли выдать вам товар.",
            "dispute_decision", "Причина: решение по вашему спору.");

    private static final Map<String, String> FAILURE_REASONS = Map.of(
            "attempts_exhausted", "письмо не принято после всех попыток отправки",
            "address_rejected", "адрес покупателя отклонён почтовым провайдером");

    private static final Pattern NUMBER = Pattern.compile("^[0-9]{1,12}$");
    private static final Pattern SIX_DIGITS = Pattern.compile("^[0-9]{6}$");
    private static final Pattern MONEY = Pattern.compile("^([0-9]{1,15}) ([A-Z]{3})$");
    private static final char NBSP = '\u00A0';

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
    private final String siteUrl;

    /** Письма со ссылкой на адрес стенда по умолчанию. */
    public Letters(ZoneId zone, String zoneLabel) {
        this(zone, zoneLabel, DEFAULT_SITE_URL);
    }

    /**
     * @param zone      часовой пояс времени в письмах
     * @param zoneLabel подпись пояса после времени, например «МСК»
     * @param siteUrl   адрес сайта без косой черты в конце: поле {@code {siteUrl}} любого шаблона (ссылки на страницы заказа)
     * @throws IllegalStateException шаблон не найден или не проходит проверку
     */
    public Letters(ZoneId zone, String zoneLabel, String siteUrl) {
        this.zone = zone;
        this.zoneLabel = zoneLabel;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        for (Map.Entry<String, Map<String, Kind>> entry : FIELDS.entrySet()) {
            templates.put(entry.getKey(), load(entry.getKey(), entry.getValue().keySet()));
        }
    }

    /** Шаблоны, которые уходят только напрямую (в письме одноразовый код): в очередь их ставить нельзя. */
    public Set<String> directOnly() {
        return DIRECT_ONLY;
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
        values.put("siteUrl", siteUrl);
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
            case NUMBER -> number(where, text);
            case MONEY -> money(where, text);
            case ID -> uuid(where, text);
            case REFUND_REASON -> fromList(where, text, REFUND_REASONS);
            case FAILURE_REASON -> fromList(where, text, FAILURE_REASONS);
            case SECONDS_AS_MINUTES -> minutes(where, text);
            case CODE -> code(where, text);
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

    private static String number(String where, String value) {
        if (!NUMBER.matcher(value).matches()) {
            throw new InvalidNotificationException(where + " должен быть целым числом без знака");
        }
        return value;
    }

    /** Сумма в минимальных единицах: «149900 RUB» превращается в «1 499,00 ₽», группы по три цифры разделены неразрывным пробелом. */
    private static String money(String where, String value) {
        Matcher matcher = MONEY.matcher(value);
        if (!matcher.matches()) {
            throw new InvalidNotificationException(where + " должен быть суммой в минимальных единицах и кодом валюты, например 149900 RUB");
        }
        String digits = matcher.group(1);
        String padded = digits.length() < 3 ? "0".repeat(3 - digits.length()) + digits : digits;
        String whole = padded.substring(0, padded.length() - 2).replaceFirst("^0+(?=.)", "");
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(NBSP);
            }
            grouped.append(whole.charAt(i));
        }
        String currency = "RUB".equals(matcher.group(2)) ? "₽" : matcher.group(2);
        return grouped + "," + padded.substring(padded.length() - 2) + NBSP + currency;
    }

    private static String code(String where, String value) {
        if (!SIX_DIGITS.matcher(value).matches()) {
            throw new InvalidNotificationException(where + " должен состоять из шести цифр");
        }
        return value;
    }

    private static String uuid(String where, String value) {
        try {
            return java.util.UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new InvalidNotificationException(where + " должен быть UUID");
        }
    }

    private static String fromList(String where, String value, Map<String, String> allowed) {
        String text = allowed.get(value);
        if (text == null) {
            throw new InvalidNotificationException(where + " не из списка известных значений");
        }
        return text;
    }

    private static String minutes(String where, String value) {
        long seconds = Long.parseLong(number(where, value));
        return Math.max(1, Math.round(seconds / 60.0)) + " мин";
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
        unknown.remove("siteUrl");
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
