package dgm.delivery.delivery.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Расписание повторов выдачи (ADR-011, ключ {@code delivery.retry-schedule}): паузы после неудачных попыток, по умолчанию
 * {@code 10s,30s,2m,5m,10m}. Первая попытка идёт сразу после оплаты, пауза после неудачи попытки {@code n} (с единицы) идёт перед попыткой
 * {@code n + 1}. Всего попыток на одну больше, чем пауз (шесть при пяти паузах): шестая неудача переводит выдачу в «ошибку» (SM-06/T4). К паузе
 * добавляется разброс ±20 %, чтобы повторы после общего сбоя провайдера не сыпались одновременно. Время берёт вызывающий из {@code Clock}.
 *
 * <p>Горизонт без разброса 17 минут 40 секунд: шестая попытка начинается на 18-й минуте и укладывается в контроль 30 минут (NFT-2.4).
 */
public final class DeliverySchedule {

    /** Доля разброса пауз. */
    public static final double JITTER = 0.2;

    /** Предел попыток на выдачу: столько позволяет таблица {@code delivery_attempt} ({@code attempt_no} от 1 до 6). */
    public static final int MAX_ATTEMPTS = 6;

    private static final Pattern PART = Pattern.compile("^(\\d+)(ms|s|m|h)$");

    private final List<Duration> pauses;
    private final double jitter;
    private final RandomGenerator random;

    private DeliverySchedule(List<Duration> pauses, double jitter, RandomGenerator random) {
        this.pauses = List.copyOf(pauses);
        this.jitter = jitter;
        this.random = random;
    }

    /**
     * @param pauses паузы после неудач 1…n, n от 1 до {@link #MAX_ATTEMPTS} минус единица, каждая больше нуля
     * @param jitter доля разброса от 0 до 1 не включая
     * @param random источник случайных чисел (в тестах с зерном)
     */
    public static DeliverySchedule of(List<Duration> pauses, double jitter, RandomGenerator random) {
        Objects.requireNonNull(pauses, "pauses");
        Objects.requireNonNull(random, "random");
        if (pauses.isEmpty() || pauses.size() > MAX_ATTEMPTS - 1) {
            throw new IllegalArgumentException("В расписании выдачи от 1 до " + (MAX_ATTEMPTS - 1) + " пауз, задано " + pauses.size());
        }
        if (jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("Разброс должен быть от 0 (включительно) до 1 (не включая)");
        }
        for (int i = 0; i < pauses.size(); i++) {
            Duration pause = pauses.get(i);
            if (pause == null || pause.isNegative() || pause.isZero()) {
                throw new IllegalArgumentException("Пауза после неудачной попытки " + (i + 1) + " должна быть больше нуля");
            }
        }
        return new DeliverySchedule(pauses, jitter, random);
    }

    /** Разбирает строку настройки вида {@code 10s,30s,2m,5m,10m} (единицы ms, s, m, h). */
    public static DeliverySchedule parse(String schedule, double jitter, RandomGenerator random) {
        if (schedule == null || schedule.isBlank()) {
            throw new IllegalArgumentException("Расписание выдачи не задано");
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

    /** Сколько попыток в расписании: после неудачи последней выдача переходит в «ошибку». */
    public int maxAttempts() {
        return pauses.size() + 1;
    }

    /** Паузы без разброса, как записаны в настройке. */
    public List<Duration> basePauses() {
        return pauses;
    }

    /** Пауза после неудачной попытки {@code failedAttemptNo} (с единицы) с разбросом. */
    public Duration pauseAfter(int failedAttemptNo) {
        if (failedAttemptNo < 1 || failedAttemptNo > pauses.size()) {
            throw new IndexOutOfBoundsException("После попытки " + failedAttemptNo + " пауз в расписании нет, всего пауз " + pauses.size());
        }
        Duration base = pauses.get(failedAttemptNo - 1);
        if (jitter == 0) {
            return base;
        }
        double factor = 1 - jitter + 2 * jitter * random.nextDouble();
        return Duration.ofNanos(Math.round(base.toNanos() * factor));
    }

    /** Момент следующей попытки, если попытка {@code failedAttemptNo} закончилась неудачей в {@code after}. */
    public Instant dueAfter(int failedAttemptNo, Instant after) {
        return after.plus(pauseAfter(failedAttemptNo));
    }

    /** Исчерпана ли очередь повторов после неудачи попытки {@code failedAttemptNo}. */
    public boolean exhaustedAfter(int failedAttemptNo) {
        return failedAttemptNo >= maxAttempts();
    }

    /** Сумма пауз без разброса: горизонт повторов. */
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
}
