package dgm.platform.identity.otp.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Временное состояние одноразовых кодов: сами коды и счётчики запросов (ADR-010). Всё, что должно быть неделимым (допуск запроса сразу по
 * нескольким окнам, проверка вместе с погашением), выполняет хранилище одним действием; время приходит параметром, поэтому тесты ведут
 * его своими часами. Реализация на Redis ({@code RedisOtpStore}) работает скриптами Lua, тестовая в памяти повторяет её правила.
 * Недоступность хранилища превращается в 503 на месте: без счётчиков SMS не отправляется.
 */
public interface OtpStore {

    /**
     * Окно ограничения частоты.
     *
     * @param key     ключ в хранилище
     * @param counter {@code true}: счётчик с фиксированным окном, которое начинается с первого запроса (потолок SMS); {@code false}:
     *                скользящее окно по меткам запросов
     * @param span    длина окна
     * @param limit   сколько запросов допускается в окне
     */
    record Window(String key, boolean counter, Duration span, int limit) {

        public static Window sliding(String key, Duration span, int limit) {
            return new Window(key, false, span, limit);
        }

        public static Window counter(String key, Duration span, int limit) {
            return new Window(key, true, span, limit);
        }
    }

    /**
     * Итог допуска.
     *
     * @param allowed     запрос допущен и записан во все окна
     * @param retryAfter  если не допущен: через сколько освободится самое долгое из исчерпанных окон
     * @param remaining   если допущен: сколько запросов ещё допустимо в самом тесном скользящем окне
     * @param resendAfter если допущен и {@code remaining} равно нулю: через сколько это окно освободится
     */
    record Admission(boolean allowed, Duration retryAfter, int remaining, Duration resendAfter) {

        public static Admission denied(Duration retryAfter) {
            return new Admission(false, retryAfter, 0, Duration.ZERO);
        }

        public static Admission allowed(int remaining, Duration resendAfter) {
            return new Admission(true, Duration.ZERO, remaining, resendAfter);
        }
    }

    /**
     * Запись кода. Сам код не хранится, только его HMAC.
     *
     * @param hash           HMAC кода с секретом {@code otp_pepper}
     * @param expiresAt      момент, после которого код не принимается
     * @param attempts       сколько попыток ввода осталось
     * @param userId         учётная запись, для которой выпущен код; пусто у кода-приманки
     * @param confirmationId идентификатор подтверждения телефона; пусто у кода входа
     * @param phone          номер, ожидающий подтверждения (только у подтверждения телефона: в базе номера до проверки нет)
     */
    record Code(String hash, Instant expiresAt, int attempts, String userId, String confirmationId, String phone) {

        /** В журнал и сообщения идёт только признак записи. */
        @Override
        public String toString() {
            return "Code[attempts=" + attempts + "]";
        }
    }

    /** Исход проверки кода. */
    enum Result {
        /** Верно, код погашен. */
        VALID,
        /** Неверно, попытки остались. */
        INVALID,
        /** Неверно, попытки кончились, код погашен. */
        EXHAUSTED,
        /** Срок кода истёк. */
        EXPIRED,
        /** Кода нет (не запрашивали, погашен, пропал вместе с Redis). */
        ABSENT,
        /** Код относится к другому подтверждению. */
        OTHER,
        /** Номер закрыт для входа по SMS, проверка не выполнялась. */
        LOCKED
    }

    /**
     * Итог проверки.
     *
     * @param result       исход
     * @param userId       при {@link Result#VALID}: учётная запись кода
     * @param phone        при {@link Result#VALID}: номер из записи (у кода входа пусто)
     * @param attemptsLeft при {@link Result#INVALID}: сколько попыток осталось
     * @param lockedFor    при {@link Result#LOCKED} и {@link Result#EXHAUSTED}: на сколько закрыт номер (ноль, если не закрыт)
     */
    record Redemption(Result result, String userId, String phone, int attemptsLeft, Duration lockedFor) {

        public static Redemption of(Result result) {
            return new Redemption(result, "", "", 0, Duration.ZERO);
        }

        @Override
        public String toString() {
            return "Redemption[" + result + "]";
        }
    }

    /**
     * Учёт кодов, погашенных исчерпанием попыток, для входа по SMS: {@code burnLimit} таких кодов за {@code burnWindow} закрывают номер на
     * {@code lockFor}. Верный код обнуляет счёт («подряд»).
     */
    record Tracking(String burnKey, String lockKey, int burnLimit, Duration burnWindow, Duration lockFor) {
    }

    /**
     * Запрос проверки.
     *
     * @param codeKey        запись кода
     * @param hash           HMAC введённого кода
     * @param confirmationId ожидаемый идентификатор подтверждения; пустая строка: не сверять
     * @param tracking       учёт погашенных кодов и замок номера; пусто для подтверждения телефона
     */
    record Redeem(String codeKey, String hash, String confirmationId, Optional<Tracking> tracking) {
    }

    /** Допускает запрос, если ни одно окно не исчерпано, и записывает его во все окна. Отказ ничего не расходует. */
    Admission admit(List<Window> windows, Instant now);

    /** Записывает код. Прежний код того же ключа погашается заменой (новый запрос гасит прежний). */
    void put(String key, Code code, Duration retention);

    /** Удаляет запись (SMS не отправлено, код вручить некому). */
    void remove(String key);

    /** Проверяет и гасит код неделимо: из параллельных попыток с верным кодом успешна ровно одна. */
    Redemption redeem(Redeem request, Instant now);

    /** На сколько ещё закрыт номер; пусто, если не закрыт. */
    Optional<Duration> lockedFor(String lockKey, Instant now);

    /**
     * Ставит метку на {@code ttl}, если её не было.
     *
     * @return {@code true}, если метки не было и она поставлена; {@code false}, если уже стоит (повторное уведомление провайдера)
     */
    boolean markOnce(String key, Duration ttl);
}
