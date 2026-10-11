package dgm.kit.idempotency;

import dgm.kit.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link IdempotencyStore} на таблице {@code idempotency_key}. Все запросы работают под рабочей ролью модуля. */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    /** Размер пачки удаления при очистке. */
    public static final int BATCH = 1000;

    private static final String CLAIM = "insert into idempotency_key (scope, key, endpoint, request_hash, state) "
            + "values (?, ?, ?, ?, 'in_progress') on conflict do nothing";
    private static final String FIND = "select endpoint, request_hash, state, response_code, response_body::text, response_headers::text "
            + "from idempotency_key where scope = ? and key = ?";
    private static final String TAKE_OVER = "update idempotency_key set created_at = now() where scope = ? and key = ? "
            + "and state = 'in_progress' and created_at < now() - make_interval(secs => ?)";
    private static final String COMPLETE = "update idempotency_key set state = 'completed', response_code = ?, response_body = ?::jsonb, "
            + "response_headers = ?::jsonb where scope = ? and key = ? and state = 'in_progress'";
    private static final String RELEASE = "delete from idempotency_key where scope = ? and key = ? and state = 'in_progress'";
    private static final String PURGE = "delete from idempotency_key where (scope, key) in (select scope, key from idempotency_key "
            + "where created_at < now() - make_interval(secs => ?) order by created_at limit ?)";

    private final JdbcTemplate jdbc;

    public JdbcIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(IdempotencyRequest request) {
        return jdbc.update(CLAIM, request.scope(), request.key(), request.endpoint(), request.requestHash()) == 1;
    }

    @Override
    public Optional<Entry> find(String scope, UUID key) {
        List<Entry> found = jdbc.query(FIND, (rs, row) -> {
            boolean completed = "completed".equals(rs.getString("state"));
            StoredResponse response = null;
            if (completed) {
                response = new StoredResponse(rs.getInt("response_code"), rs.getString(5), headers(rs.getString(6)));
            }
            return new Entry(rs.getString("endpoint"), rs.getBytes("request_hash"), completed, response);
        }, scope, key);
        return found.stream().findFirst();
    }

    @Override
    public boolean takeOver(String scope, UUID key, long abandonedAfterSeconds) {
        return jdbc.update(TAKE_OVER, scope, key, (double) abandonedAfterSeconds) == 1;
    }

    @Override
    public void complete(IdempotencyRequest request, StoredResponse response) {
        String headers = response.headers().isEmpty() ? null : Json.write(response.headers());
        int updated = jdbc.update(COMPLETE, response.status(), response.body(), headers, request.scope(), request.key());
        if (updated != 1) {
            // Ключ освобождён или подхвачен другим запросом: фиксировать изменение нельзя, транзакция действия откатывается
            throw new IllegalStateException("Ключ идемпотентности больше не принадлежит этому запросу");
        }
    }

    @Override
    public void release(IdempotencyRequest request) {
        jdbc.update(RELEASE, request.scope(), request.key());
    }

    @Override
    public int purge(long olderThanSeconds) {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update(PURGE, (double) olderThanSeconds, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }

    private static Map<String, String> headers(String json) {
        if (json == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Json.parseObject(json).forEach((name, value) -> headers.put(name, String.valueOf(value)));
        return headers;
    }
}
