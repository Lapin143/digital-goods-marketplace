package dgm.kit.jdbc;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Помощник фоновых исполнителей: взять до N строк, которые пора обработать, так, чтобы два экземпляра сервиса не взяли одну и ту же
 * (правило 15 руководства среза 2, NFT-1.3, ADR-007). Им пользуются диспетчер выдачи, исполнитель возвратов, сторож заказов, контроль
 * 30 минут и сверка резервов.
 *
 * <p>Два способа, выбор зависит от того, что исполнитель делает со строкой:
 * <ul>
 *   <li>{@link #claim}: <b>аренда</b>. Один запрос {@code WITH picked AS MATERIALIZED (SELECT … FOR UPDATE SKIP LOCKED) UPDATE … RETURNING}
 *       сдвигает срок строки вперёд на время аренды и сразу фиксируется. Дальше исполнитель работает со строкой <b>вне транзакции</b>
 *       (вызывает соседа, шлёт письмо) и в конце коротко записывает итог. Если процесс упал, срок аренды истекает, и строка снова
 *       становится «наступившей»: работа не теряется, а повторяется (хотя бы один раз). Транзакция при этом не держится на время
 *       вызова соседа (правило 9);</li>
 *   <li>{@link #lock}: <b>блокировка в транзакции</b>. {@code SELECT … FOR UPDATE SKIP LOCKED} в текущей транзакции без изменения
 *       строк, подходит работе, которая целиком укладывается в эту же транзакцию (запись события и смена статуса). Чужие экземпляры
 *       заблокированные строки пропускают, а не ждут.</li>
 * </ul>
 *
 * <p>Время берётся из переданных часов, а не из {@code now()} базы (принцип 4 стратегии): так тесты и профиль {@code fast-time} двигают
 * его сами. Имена таблиц и столбцов и условия задаются кодом сервиса, не пользовательскими данными; они проверяются на допустимые
 * символы, значения передаются параметрами.
 *
 * <p>Порядок строк в результате {@link #claim} не гарантируется ({@code RETURNING} его не сохраняет): если он важен, исполнитель
 * сортирует результат сам.
 */
public final class RowClaim {

    /** Предел пачки: больше строк за раз берут отдельными вызовами. */
    public static final int MAX_BATCH = 1000;

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern TABLE = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?");

    private final String table;
    private final String claimSql;
    private final String lockSql;

    private RowClaim(String table, String claimSql, String lockSql) {
        this.table = table;
        this.claimSql = claimSql;
        this.lockSql = lockSql;
    }

    /** Начинает описание очереди в таблице {@code table} (можно со схемой: {@code delivery.delivery}). */
    public static Builder on(String table) {
        return new Builder(table);
    }

    /** Описание очереди. */
    public static final class Builder {

        private final String table;
        private String idColumn = "id";
        private String ready;
        private String dueColumn;
        private String leaseColumn;
        private String order;
        private String extraSet;
        private String returning;

        private Builder(String table) {
            if (table == null || !TABLE.matcher(table).matches()) {
                throw new IllegalArgumentException("Недопустимое имя таблицы: " + table);
            }
            this.table = table;
        }

        /** Столбец уникального идентификатора строки (по умолчанию {@code id}); составных ключей помощник не поддерживает. */
        public Builder idColumn(String column) {
            this.idColumn = requireIdentifier(column, "idColumn");
            return this;
        }

        /** Условие «строка в очереди», например {@code status = 'queued'}; условие срока добавляется отдельно. */
        public Builder where(String condition) {
            this.ready = requireFragment(condition, "where");
            return this;
        }

        /** Столбец срока: строка наступила, когда {@code dueColumn <= now}. По нему же по умолчанию сортируется выбор. */
        public Builder dueColumn(String column) {
            this.dueColumn = requireIdentifier(column, "dueColumn");
            return this;
        }

        /** Столбец, который аренда сдвигает вперёд (по умолчанию тот же, что срок). */
        public Builder leaseColumn(String column) {
            this.leaseColumn = requireIdentifier(column, "leaseColumn");
            return this;
        }

        /** Порядок выбора (по умолчанию {@code срок, идентификатор}), например {@code created_at, id}. */
        public Builder orderBy(String expression) {
            this.order = requireFragment(expression, "orderBy");
            return this;
        }

        /** Дополнительное присваивание при аренде, например {@code status = 'in_progress'}. */
        public Builder alsoSet(String assignment) {
            this.extraSet = requireFragment(assignment, "alsoSet");
            return this;
        }

        /** Столбцы результата через запятую (по умолчанию все столбцы таблицы). */
        public Builder returning(String columns) {
            this.returning = requireFragment(columns, "returning");
            return this;
        }

        public RowClaim build() {
            if (ready == null) {
                throw new IllegalStateException("Не задано условие очереди where(...)");
            }
            if (dueColumn == null) {
                throw new IllegalStateException("Не задан столбец срока dueColumn(...)");
            }
            String lease = leaseColumn == null ? dueColumn : leaseColumn;
            String sortBy = order == null ? dueColumn + ", " + idColumn : order;
            String select = "select " + idColumn + " as claim_id from " + table + " where (" + ready + ") and " + dueColumn
                    + " <= ? order by " + sortBy + " limit ? for update skip locked";
            String claim = "with picked as materialized (" + select + ") update " + table + " set " + lease + " = ?"
                    + (extraSet == null ? "" : ", " + extraSet) + " from picked where " + table + "." + idColumn
                    + " = picked.claim_id returning " + (returning == null ? table + ".*" : returning);
            String lock = "select " + (returning == null ? "*" : returning) + " from " + table + " where (" + ready + ") and " + dueColumn
                    + " <= ? order by " + sortBy + " limit ? for update skip locked";
            return new RowClaim(table, claim, lock);
        }

        private static String requireIdentifier(String value, String what) {
            if (value == null || !IDENTIFIER.matcher(value).matches()) {
                throw new IllegalArgumentException("Недопустимое имя столбца в " + what + ": " + value);
            }
            return value;
        }

        private static String requireFragment(String value, String what) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Пустое выражение в " + what);
            }
            if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/") || value.contains("?")) {
                throw new IllegalArgumentException("Недопустимые символы в выражении " + what + " (параметры и комментарии не допускаются)");
            }
            return value;
        }
    }

    /**
     * Берёт в аренду до {@code limit} наступивших строк: сдвигает столбец аренды на {@code now + lease} (и выполняет {@code alsoSet}).
     * Вызывать <b>без внешней транзакции</b> (запрос сам себе транзакция): держать транзакцию открытой на время работы со строками
     * нельзя, тогда аренда теряет смысл. Если транзакция всё же есть, строки останутся заблокированными до её конца.
     *
     * @param now   текущее время по часам сервиса
     * @param lease срок аренды: через него необработанная строка снова наступит
     * @param limit сколько строк взять, от 1 до {@link #MAX_BATCH}
     * @return взятые строки; другой экземпляр их не получит, пока аренда не истекла
     */
    public <T> List<T> claim(JdbcTemplate jdbc, Instant now, Duration lease, int limit, RowMapper<T> mapper) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(mapper, "mapper");
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("Срок аренды должен быть больше нуля");
        }
        requireLimit(limit);
        return jdbc.query(claimSql, mapper, utc(now), limit, utc(now.plus(lease)));
    }

    /**
     * Блокирует до {@code limit} наступивших строк в текущей транзакции и возвращает их по порядку выбора; строки, заблокированные
     * другим экземпляром, пропускаются. Блокировка держится до конца транзакции.
     *
     * @throws IllegalStateException если транзакции нет: блокировка без транзакции снимается сразу и ничего не защищает
     */
    public <T> List<T> lock(JdbcTemplate jdbc, Instant now, int limit, RowMapper<T> mapper) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(mapper, "mapper");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Блокировка строк работает только внутри транзакции");
        }
        requireLimit(limit);
        return jdbc.query(lockSql, mapper, utc(now), limit);
    }

    /** Текст запроса аренды: для тестов и разбора плана (EXPLAIN). */
    public String claimSql() {
        return claimSql;
    }

    /** Текст запроса блокировки: для тестов и разбора плана (EXPLAIN). */
    public String lockSql() {
        return lockSql;
    }

    public String table() {
        return table;
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_BATCH) {
            throw new IllegalArgumentException("Размер пачки от 1 до " + MAX_BATCH);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
