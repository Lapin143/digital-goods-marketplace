package dgm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dgm.kit.boot.testing.StandHttp.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * ST-01, канарейка значений ключей (NFT-3.2, ADR-009, IT-7 стратегии тестирования). Загружаются значения вида {@code CANARY-<случайное>},
 * после чего они ищутся там, где значения ключей быть не вправе: в каждом ответе API продавца (в том числе в ответах на ошибочные запросы),
 * в журнале сервиса с повышенным уровнем (TRACE у {@code dgm} и {@code org.springframework.jdbc}: журнал показывает значения параметров
 * SQL), в событиях Outbox и в каждой текстовой, JSON и байтовой колонке базы {@code inventory_db}. Допустимое место одно: шифртекст в
 * {@code inventory.key.ciphertext}, а там открытого значения тоже нет. Искомое берётся в нескольких видах: как есть, в шестнадцатеричной
 * записи (так байты показывает {@code bytea::text}) и в Base64 (так байты кладут в JSON). Нечестный поиск не защищает, поэтому у каждого
 * поиска есть контрольный образец, который обязан найтись.
 *
 * <p>То же по журналам всех контейнеров, теме {@code inventory.events} и дампу базы стенда проверяет {@code make canary-check}.
 */
class KeyCanaryIT extends KeyStandIT {

    private static final String PREFIX = "CANARY-";
    private static final HexFormat HEX = HexFormat.of();

    private static String canary() {
        return PREFIX + UUID.randomUUID();
    }

    /** Что искать: значения целиком, общий префикс, их запись в hex и в Base64 (обычном и для адресов). */
    private static List<String> needles(List<String> values) {
        List<String> needles = new ArrayList<>(values);
        needles.add(PREFIX);
        needles.add(HEX.formatHex(PREFIX.getBytes(StandardCharsets.UTF_8)));
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            needles.add(HEX.formatHex(bytes));
            needles.add(Base64.getEncoder().encodeToString(bytes));
            needles.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }
        return needles;
    }

    private static List<String> found(String haystack, List<String> needles) {
        List<String> found = new ArrayList<>();
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                found.add(needle);
            }
        }
        return found;
    }

    private static String render(Response response) {
        return response.status() + " " + response.headers().map() + " " + response.body();
    }

    private static String render(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getLoggerName()).append(' ').append(event.getFormattedMessage());
        for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
            text.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        for (IThrowableProxy error = event.getThrowableProxy(); error != null; error = error.getCause()) {
            text.append(' ').append(error.getClassName()).append(": ").append(error.getMessage());
        }
        return text.toString();
    }

    /** Колонки базы, где мог бы лежать текст: text, varchar, json(b) и bytea в схемах {@code inventory} и {@code public}. */
    private static List<String> textColumns() {
        List<String> columns = new ArrayList<>();
        for (Map<String, Object> row : db.queryForList("select table_schema, table_name, column_name from information_schema.columns "
                + "where table_schema in ('inventory', 'public') and data_type in ('text', 'character varying', 'character', 'json', 'jsonb', 'bytea') "
                + "order by 1, 2, 3")) {
            columns.add(row.get("table_schema") + "." + row.get("table_name") + "." + row.get("column_name"));
        }
        return columns;
    }

    /** В каких колонках базы встречается хотя бы одна из строк. Байты сравниваются в записи {@code bytea::text}: это {@code \x} и строчные hex. */
    private static List<String> columnsWith(List<String> needles) {
        List<String> hits = new ArrayList<>();
        for (String column : textColumns()) {
            String[] part = column.split("\\.");
            StringBuilder where = new StringBuilder();
            for (int i = 0; i < needles.size(); i++) {
                where.append(i == 0 ? "" : " or ").append("strpos(\"").append(part[2]).append("\"::text, ?) > 0");
            }
            Integer n = db.queryForObject("select count(*) from \"" + part[0] + "\".\"" + part[1] + "\" where " + where, Integer.class, needles.toArray());
            if (n != null && n > 0) {
                hits.add(column);
            }
        }
        return hits;
    }

    @Tag("TC-412")
    @Test
    void uploadedValuesAreFoundNowhereExceptTheEncryptedColumn() throws Exception {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            values.add(canary());
        }
        String tooLong = PREFIX + "x".repeat(1100);
        List<String> needles = needles(values);
        needles.add(tooLong);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Map<String, Level> loud = new LinkedHashMap<>();
        loud.put("dgm", Level.TRACE);
        loud.put("org.springframework.jdbc", Level.TRACE);
        loud.put("org.springframework.web", Level.DEBUG);
        loud.put("org.springframework.security", Level.DEBUG);
        loud.put("org.springframework.transaction", Level.DEBUG);
        Map<String, Level> before = new LinkedHashMap<>();
        for (Map.Entry<String, Level> entry : loud.entrySet()) {
            Logger logger = context.getLogger(entry.getKey());
            before.put(entry.getKey(), logger.getLevel());
            logger.setLevel(entry.getValue());
        }
        ListAppender<ILoggingEvent> journal = new ListAppender<>();
        journal.setContext(context);
        journal.start();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(journal);

        Seller seller = new Seller();
        Seller stranger = new Seller();
        UUID productId = seller.product();
        List<Response> responses = new ArrayList<>();
        try {
            // Загрузка списком: пять новых значений, повтор первого и пустая строка
            Response first = batch(seller, productId, values.get(0), values.get(1), values.get(2), values.get(3), values.get(4), values.get(0), "   ");
            responses.add(first);
            assertEquals(200, first.status(), first.body());
            assertEquals(5, first.json().get("accepted"));
            assertEquals(List.of(line(6, "duplicate_in_upload"), line(7, "empty")), rejected(first));

            // Те же значения ещё раз: все уже в пуле
            Response again = batch(seller, productId, values.get(0), values.get(1));
            responses.add(again);
            assertEquals(0, again.json().get("accepted"));

            // Файл CSV с переводом строки Windows: одно новое значение и одно старое, слишком длинная строка
            Response csv = file(seller, productId, values.get(5) + "\r\n" + values.get(1) + "\r\n" + tooLong + "\r\n");
            responses.add(csv);
            assertEquals(200, csv.status(), csv.body());
            assertEquals(1, csv.json().get("accepted"));
            assertEquals(List.of(line(2, "duplicate_in_pool"), line(3, "too_long")), rejected(csv));

            // Ошибочные запросы с теми же значениями в теле: ни один ответ их не повторяет
            String path = PRODUCTS + "/" + productId + "/key-batches";
            responses.add(gateway.post(path, "{\"keys\":[\"" + values.get(0) + "\"", with(seller.auth, "Idempotency-Key", key())));
            responses.add(gateway.post(path, "{\"keys\":[\"" + values.get(0) + "\", 7]}", with(seller.auth, "Idempotency-Key", key())));
            responses.add(gateway.request("POST", path, "text/plain", values.get(0).getBytes(StandardCharsets.UTF_8), with(seller.auth, "Idempotency-Key", key())));
            responses.add(gateway.post(path, batchBody(values.get(0)), "Idempotency-Key", key()));
            responses.add(gateway.post(PRODUCTS + "/" + UUID.randomUUID() + "/key-batches", batchBody(values.get(0)), with(seller.auth, "Idempotency-Key", key())));
            responses.add(gateway.post(path, batchBody(values.get(0)), with(stranger.auth, "Idempotency-Key", key())));
            responses.add(stock(seller, productId));
            responses.add(stock(stranger, productId));

            // Фоновые потоки (публикатор Outbox) успевают записать событие остатка
            assertTrue(await(Duration.ofSeconds(20), () -> !stockEvents(productId).isEmpty()), "событие остатка записано в Outbox");
        } finally {
            root.detachAppender(journal);
            journal.stop();
            for (Map.Entry<String, Level> entry : before.entrySet()) {
                context.getLogger(entry.getKey()).setLevel(entry.getValue());
            }
        }

        // 1. Ответы API: контрольный образец, что мы читаем настоящие ответы, а не пустоту
        assertTrue(responses.get(0).body().contains("rejected"));
        for (Response response : responses) {
            assertEquals(List.of(), found(render(response), needles), "в ответе API нашлось значение ключа: " + response.status() + " " + response.body());
        }

        // 2. Журнал сервиса
        List<ILoggingEvent> events = new ArrayList<>(journal.list);
        assertFalse(events.isEmpty(), "контроль: перехват журнала работает, события есть");
        for (ILoggingEvent event : events) {
            String text = render(event);
            assertEquals(List.of(), found(text, needles), "в журнале нашлось значение ключа: " + text);
        }

        // 3. База: контрольный образец (идентификатор товара есть в событиях Outbox) и сам поиск
        assertTrue(columnsWith(List.of(productId.toString())).contains("public.outbox.payload")
                || columnsWith(List.of(productId.toString())).contains("public.outbox.aggregate_id"), "контроль: поиск по колонкам находит идентификатор товара в Outbox");
        assertEquals(List.of(), columnsWith(needles), "значения ключей в открытом виде лежат в колонках базы");

        // 4. Единственное место значений на месте: пять шифртекстов и ничего лишнего
        assertEquals(6, count(productId));
        Integer encrypted = db.queryForObject("select count(*) from inventory.key where product_id = ? and octet_length(ciphertext) > 16", Integer.class, productId);
        assertEquals(6, encrypted);
    }
}
