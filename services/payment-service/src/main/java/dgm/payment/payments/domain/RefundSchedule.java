package dgm.payment.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Расписание автоматического возврата (ADR-015, ключ {@code payment.refund-schedule}): паузы до первой, второй и следующих попыток, по
 * умолчанию {@code 0s,30s,2m,5m,10m}. Первая попытка идёт сразу, пауза нуль разрешена только ей. К остальным паузам добавляется разброс
 * ±20 %: возвраты разных заказов после общего сбоя шлюза не выстраиваются в одну секунду (как в {@code RetryPolicy} каркаса).
 *
 * <p>Горизонт без разброса 17,5 минуты выбран так, чтобы автовозврат до очереди администратора укладывался в 30 минут (NFT-2.4). Время
 * берёт вызывающий из {@code Clock}, класс только считает паузы.
 */
public final class RefundSchedule {

    /** Доля разброса пауз. */
    public static final double JITTER = 0.2;

    private static final Pattern PART = Pattern.compile("^(\\d+)(ms|s|m|h)$");

    private final List<Duration> pauses;
    private final double jitter;
    private final RandomGenerator random;

    private RefundSchedule(List<Duration> pauses, double jitter, RandomGenerator random) {
        this.pauses = List.copyOf(pauses);
        this.jitter = jitter;
        this.random = random;
    }

    /**
     * @param pauses паузы до попыток 1…n, n от 1 до {@link RefundAttempt#MAX_ATTEMPTS}; нуль разрешён только первой
     * @param jitter доля разброса от 0 до 1 не включая
     * @param random источник случайных чисел (в тестах с зерном)
     */
    public static RefundSchedule of(List<Duration> pauses, double jitter, RandomGenerator random) {
        Objects.requireNonNull(pauses, "pauses");
        Objects.requireNonNull(random, "random");
        if (pauses.isEmpty() || pauses.size() > RefundAttempt.MAX_ATTEMPTS) {
            throw new IllegalArgumentException("В расписании возврата от 1 до " + RefundAttempt.MAX_ATTEMPTS + " пауз, задано " + pauses.size());
        }
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("Разброс должен быть от 0 (включительно) до 1 (не включая)");
        }
        for (int i = 0; i < pauses.size(); i++) {
            Duration pause = pauses.get(i);
            if (pause == null || pause.isNegative() || (i > 0 && pause.isZero())) {
                throw new IllegalArgumentException("Пауза перед попыткой " + (i + 1) + " должна быть больше нуля (нуль допустим только перед первой)");
            }
        }
        return new RefundSchedule(pauses, jitter, random);
    }

    /** Разбирает строку настройки вида {@code 0s,30s,2m,5m,10m} (единицы ms, s, m, h). */
    public static RefundSchedule parse(String schedule, double jitter, RandomGenerator random) {
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Расписание возврата не задано");
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

    /** Сколько попыток в расписании: после последней неудачи возврат уходит администратору. */
    public int maxAttempts() {
        return pauses.size();
    }

    /** Паузы без разброса, как записаны в настройке. */
    public List<Duration> basePauses() {
        return pauses;
    }

    /** Пауза до попытки с номером {@code attemptNo} (с единицы) с разбросом; нулевая пауза остаётся нулевой. */
    public Duration pauseBefore(int attemptNo) {
        Duration base = pauses.get(index(attemptNo));
        if (jitter == 0 || base.isZero()) {
            return base;
        }
        double factor = 1 - jitter + 2 * jitter * random.nextDouble();
        return Duration.ofNanos(Math.round(base.toNanos() * factor));
    }

    /** Момент, когда пора выполнять попытку {@code attemptNo}, если предыдущая закончилась в {@code after}. */
    public Instant dueAt(int attemptNo, Instant after) {
        return after.plus(pauseBefore(attemptNo));
    }

    /** Сумма пауз без разброса: горизонт автовозврата до передачи администратору. */
    public Duration horizon() {
        Duration total = Duration.ZERO;
        for (Duration pause : pauses) {
            total = total.plus(pause);
        }
        return total;
    }

    /** Сумма наибольших возможных пауз с разбросом. */
    public Duration maxHorizon() {
        return Duration.ofNanos(Math.round(horizon().toNanos() * (1 + jitter)));
    }

    private int index(int attemptNo) {
        if (attemptNo < 1 || attemptNo > pauses.size()) {
            throw new IndexOutOfBoundsException("Попытки с номером " + attemptNo + " в расписании нет, всего " + pauses.size());
        }
        return attemptNo - 1;
    }
}
