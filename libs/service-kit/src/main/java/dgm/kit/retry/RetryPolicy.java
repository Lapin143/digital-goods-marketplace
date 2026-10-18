package dgm.kit.retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Расписание повторов: список пауз перед второй, третьей и следующими попытками с разбросом (ADR-011: 10 с, 30 с, 2, 5, 10 минут ±20 %).
 * Разброс не даёт повторам разных заказов выстроиться в одну секунду после общего сбоя (эффект «стада»).
 *
 * <p>Класс только считает паузы: ожидание делает вызывающий через внедряемый {@code Sleeper} или планировщик, а время берёт из
 * {@code Clock}, поэтому тесты проходят без настоящего ожидания. Число попыток всего это число пауз плюс одна.
 */
public final class RetryPolicy {

    private static final Pattern PART = Pattern.compile("^(\\d+)(ms|s|m|h)$");
    private static final RetryPolicy NONE = new RetryPolicy(List.of(), 0, RandomGenerator.getDefault());

    private final List<Duration> pauses;
    private final double jitter;
    private final RandomGenerator random;

    private RetryPolicy(List<Duration> pauses, double jitter, RandomGenerator random) {
        this.pauses = List.copyOf(pauses);
        this.jitter = jitter;
        this.random = random;
    }

    /** Без повторов: одна попытка. */
    public static RetryPolicy none() {
        return NONE;
    }

    /**
     * @param pauses паузы перед повторами, каждая больше нуля
     * @param jitter доля разброса от 0 до 1: пауза выбирается равномерно в пределах {@code пауза × (1 ± jitter)}
     * @param random источник случайных чисел (в тестах с зерном)
     */
    public static RetryPolicy of(List<Duration> pauses, double jitter, RandomGenerator random) {
        Objects.requireNonNull(pauses, "pauses");
        Objects.requireNonNull(random, "random");
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("Разброс должен быть от 0 (включительно) до 1 (не включая)");
        }
        for (Duration pause : pauses) {
            if (pause == null || pause.isZero() || pause.isNegative()) {
                throw new IllegalArgumentException("Пауза повтора должна быть больше нуля");
            }
        }
        return new RetryPolicy(pauses, jitter, random);
    }

    /**
     * Разбирает строку настройки вида {@code 10s,30s,2m,5m,10m} (единицы: ms, s, m, h). Пустая строка это «без повторов».
     */
    public static RetryPolicy parse(String schedule, double jitter, RandomGenerator random) {
        if (schedule == null || schedule.isBlank()) {
            return of(List.of(), jitter, random);
        }
        List<Duration> pauses = new ArrayList<>();
        for (String raw : schedule.split(",")) {
            Matcher m = PART.matcher(raw.trim());
            if (!m.matches()) {
                throw new IllegalArgumentException("Пауза «" + raw.trim() + "» не разобрана, ожидается число и единица ms, s, m или h");
            }
            long value = Long.parseLong(m.group(1));
            pauses.add(switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(value);
                case "s" -> Duration.ofSeconds(value);
                case "m" -> Duration.ofMinutes(value);
                default -> Duration.ofHours(value);
            });
        }
        return of(pauses, jitter, random);
    }

    /** Сколько повторов разрешено после первой попытки. */
    public int maxRetries() {
        return pauses.size();
    }

    /** Сколько попыток всего. */
    public int maxAttempts() {
        return pauses.size() + 1;
    }

    /** Паузы без разброса, как записаны в настройке. */
    public List<Duration> basePauses() {
        return pauses;
    }

    /**
     * Пауза перед повтором с номером {@code retryIndex} (0 это пауза между первой и второй попыткой) с разбросом.
     *
     * @throws IndexOutOfBoundsException если повторов столько не разрешено
     */
    public Duration pause(int retryIndex) {
        Duration base = pauses.get(retryIndex);
        if (jitter == 0) {
            return base;
        }
        double factor = 1 - jitter + 2 * jitter * random.nextDouble();
        return Duration.ofNanos(Math.round(base.toNanos() * factor));
    }

    /** Наибольшая возможная пауза с разбросом: для расчёта предельного времени всех попыток. */
    public Duration maxPause(int retryIndex) {
        return Duration.ofNanos(Math.round(pauses.get(retryIndex).toNanos() * (1 + jitter)));
    }

    /** Сумма наибольших пауз всех повторов. */
    public Duration totalMaxPause() {
        Duration total = Duration.ZERO;
        for (int i = 0; i < pauses.size(); i++) {
            total = total.plus(maxPause(i));
        }
        return total;
    }
}
