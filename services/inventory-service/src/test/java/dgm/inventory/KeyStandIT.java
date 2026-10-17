package dgm.inventory;

import dgm.kit.boot.testing.StandHttp;
import dgm.kit.boot.testing.StandHttp.Response;
import dgm.kit.json.Json;
import dgm.kit.testing.Stand;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Общие помощники интеграционных тестов пула ключей: клиент шлюза, прямое подключение к базе под ролью миграций (у неё предел 2
 * соединения, поэтому из параллельных потоков в базу не ходим, только через HTTP), продавец с токеном и товаром в реестре, разбор ответов.
 */
abstract class KeyStandIT extends InventoryStandIT {

    static final String PRODUCTS = "/api/v1/seller/products";

    static StandHttp gateway;
    static JdbcTemplate db;

    @BeforeAll
    static void connect() {
        gateway = StandHttp.asService("api-gateway", PORT);
        db = new JdbcTemplate(Stand.dataSource("migrator_inventory", "inventory_db"));
    }

    /** Продавец с токеном кабинета: роль seller, область seller.catalog, второй фактор и claim seller_id. */
    static final class Seller {
        final UUID sellerId = UUID.randomUUID();
        final String[] auth = StandHttp.bearer(TOKENS.token().subject(UUID.randomUUID().toString()).scopes("seller.catalog").roles("seller")
                .amr("pwd", "otp").sellerId(sellerId.toString()).build());

        /** Товар продавца в реестре: копия, как её завёл бы обработчик событий каталога. */
        UUID product(String status, String issuance) {
            UUID productId = UUID.randomUUID();
            db.update("insert into inventory.product_copy (product_id, seller_id, issuance_method, status, source_version) values (?, ?, ?, ?, 1)",
                    productId, sellerId, issuance, status);
            return productId;
        }

        UUID product() {
            return product("draft", "key_pool");
        }
    }

    static String[] with(String[] auth, String... extra) {
        String[] all = new String[auth.length + extra.length];
        System.arraycopy(auth, 0, all, 0, auth.length);
        System.arraycopy(extra, 0, all, auth.length, extra.length);
        return all;
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String code(Response response) {
        return (String) response.json().get("code");
    }

    static String batchBody(String... keys) {
        List<Object> list = new ArrayList<>(List.of((Object[]) keys));
        return Json.write(Map.of("keys", list));
    }

    static Response batch(Seller seller, UUID productId, String... keys) throws IOException {
        return gateway.post(PRODUCTS + "/" + productId + "/key-batches", batchBody(keys), with(seller.auth, "Idempotency-Key", key()));
    }

    static Response file(Seller seller, UUID productId, String csv) throws IOException {
        return gateway.request("POST", PRODUCTS + "/" + productId + "/key-files", "text/csv", csv.getBytes(StandardCharsets.UTF_8),
                with(seller.auth, "Idempotency-Key", key()));
    }

    static Response stock(Seller seller, UUID productId) throws IOException {
        return gateway.get(PRODUCTS + "/" + productId + "/stock", seller.auth);
    }

    static List<Map<String, Object>> rejected(Response response) {
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (Object item : (List<?>) response.json().get("rejected")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> line = (Map<String, Object>) item;
            rejected.add(line);
        }
        return rejected;
    }

    static Map<String, Object> line(int number, String reason) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("line", number);
        line.put("reason", reason);
        return line;
    }

    static List<Map<String, Object>> stockEvents(UUID productId) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String payload : db.queryForList("select payload::text from public.outbox where aggregate_id = ? and event_type = 'stock.changed' order by id",
                String.class, productId.toString())) {
            events.add(Json.parseObject(payload));
        }
        return events;
    }

    static int count(UUID productId) {
        Integer n = db.queryForObject("select count(*) from inventory.key where product_id = ?", Integer.class, productId);
        return n == null ? 0 : n;
    }

    /**
     * Доводит пул товара до {@code total} ключей прямой вставкой: шифртекст-заглушка допустимой длины, HMAC из md5 (уникален в пределах
     * товара и не пересекается с настоящими HMAC-SHA-256). В пуле уже должен быть хотя бы один ключ: он создаёт ключ данных.
     */
    static void fillPool(UUID productId, int total) {
        Integer dekId = db.queryForObject("select dek_id from inventory.key where product_id = ? limit 1", Integer.class, productId);
        int missing = total - count(productId);
        db.update("insert into inventory.key (id, product_id, dek_id, nonce, ciphertext, hmac) "
                + "select gen_random_uuid(), ?, ?, decode('000000000000000000000000', 'hex'), decode(repeat('00', 17), 'hex'), "
                + "decode(md5(i::text) || md5(i::text), 'hex') from generate_series(1, ?) i", productId, dekId, missing);
    }

    /** Убирает ключи товара после теста с большим пулом, чтобы база стенда не росла от прогона к прогону (владелец таблицы удалять может, рабочая роль нет). */
    static void dropPool(UUID productId) {
        db.update("delete from inventory.key where product_id = ?", productId);
    }

    /** Сколько значений в пуле товара встречается больше одного раза: по базе, а не по ответам потоков. */
    static int duplicateValues(UUID productId) {
        Integer n = db.queryForObject("select count(*) from (select hmac from inventory.key where product_id = ? group by hmac having count(*) > 1) d",
                Integer.class, productId);
        return n == null ? 0 : n;
    }

    interface Condition {
        boolean holds() throws Exception;
    }

    static boolean await(Duration limit, Condition condition) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.holds();
    }
}
