package dgm.kit.idempotency;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Хранилище ключей в памяти для модульных тестов: ту же логику на настоящей базе проверяет IdempotencyGuardIT. */
final class InMemoryIdempotencyStore implements IdempotencyStore {

    private record Row(String endpoint, byte[] hash, boolean completed, StoredResponse response) {
    }

    private final Map<String, Row> rows = new HashMap<>();
    /** Журнал вызовов, чтобы тесты видели порядок: claim, complete, release. */
    final List<String> calls = new ArrayList<>();
    /** Ответ на {@link #takeOver}: тест решает, считается ли запрос брошенным. */
    boolean abandoned;
    /** Если задано, {@link #complete} бросает это исключение, как при потерянном ключе. */
    RuntimeException completeFailure;
    /** Вызывается при каждом {@link #find}: тест завершает «чужой» запрос в нужный момент. */
    Runnable onFind = () -> { };

    private static String id(String scope, UUID key) {
        return scope + "/" + key;
    }

    @Override
    public synchronized boolean claim(IdempotencyRequest request) {
        calls.add("claim");
        String id = id(request.scope(), request.key());
        if (rows.containsKey(id)) {
            return false;
        }
        rows.put(id, new Row(request.endpoint(), request.requestHash(), false, null));
        return true;
    }

    @Override
    public synchronized Optional<Entry> find(String scope, UUID key) {
        calls.add("find");
        onFind.run();
        Row row = rows.get(id(scope, key));
        return row == null ? Optional.empty() : Optional.of(new Entry(row.endpoint(), row.hash(), row.completed(), row.response()));
    }

    @Override
    public synchronized boolean takeOver(String scope, UUID key, long abandonedAfterSeconds) {
        calls.add("takeOver");
        Row row = rows.get(id(scope, key));
        return abandoned && row != null && !row.completed();
    }

    @Override
    public synchronized void complete(IdempotencyRequest request, StoredResponse response) {
        calls.add("complete");
        if (completeFailure != null) {
            throw completeFailure;
        }
        String id = id(request.scope(), request.key());
        Row row = rows.get(id);
        rows.put(id, new Row(row.endpoint(), row.hash(), true, response));
    }

    @Override
    public synchronized void release(IdempotencyRequest request) {
        calls.add("release");
        Row row = rows.get(id(request.scope(), request.key()));
        if (row != null && !row.completed()) {
            rows.remove(id(request.scope(), request.key()));
        }
    }

    @Override
    public synchronized int purge(long olderThanSeconds) {
        int n = rows.size();
        rows.clear();
        return n;
    }

    synchronized int size() {
        return rows.size();
    }

    /** Помечает чужой запрос завершённым: так выглядит конец первого выполнения для ожидающего повтора. */
    synchronized void finishExternally(String scope, UUID key, StoredResponse response) {
        String id = id(scope, key);
        Row row = rows.get(id);
        rows.put(id, new Row(row.endpoint(), row.hash(), true, response));
    }

    @Override
    public String toString() {
        return "InMemoryIdempotencyStore" + Arrays.toString(rows.keySet().toArray());
    }
}
