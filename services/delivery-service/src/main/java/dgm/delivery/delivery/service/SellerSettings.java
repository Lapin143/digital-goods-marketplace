package dgm.delivery.delivery.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Настройки выдачи по запросу к системе продавца (ADR-028, шаг 12 руководства Ф5): окно ответа (параметр платформы
 * {@code delivery.seller-response-seconds}, 600 секунд, границы от 30 до 900), аренда запроса на время отправки, аренда на приём ответа, размеры пачек
 * и паузы между отправками (5 и 30 секунд, всего три отправки внутри окна, Р-191).
 *
 * @param response    окно ответа продавца от оплаты (в {@code fast-time} сокращается)
 * @param sendLease   через столько упавший процесс теряет запрос в отправке, и его берёт другой экземпляр
 * @param answerLease аренда на приём ответа: ответ, начатый до конца окна, имеет право закончиться (Р-190); больше срока вызова хранилища ключей
 * @param batch       сколько запросов отправитель берёт за проход
 * @param timeoutBatch сколько просроченных запросов исполнитель тайм-аута берёт за проход
 */
public record SellerSettings(Duration response, Duration sendLease, Duration answerLease, int batch, int timeoutBatch) {

    /** Паузы после первой и второй неудачной отправки; после третьей запроса ждёт тайм-аут. */
    private static final Duration[] PAUSES = {Duration.ofSeconds(5), Duration.ofSeconds(30)};

    public SellerSettings {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(sendLease, "sendLease");
        Objects.requireNonNull(answerLease, "answerLease");
        if (response.compareTo(Duration.ofSeconds(1)) < 0 || response.compareTo(Duration.ofSeconds(900)) > 0) {
            throw new IllegalArgumentException("Окно ответа продавца от 1 до 900 секунд (параметр платформы допускает от 30)");
        }
        if (sendLease.isZero() || sendLease.isNegative() || answerLease.isZero() || answerLease.isNegative()) {
            throw new IllegalArgumentException("Аренда должна быть больше нуля");
        }
        if (batch < 1 || batch > 1000 || timeoutBatch < 1 || timeoutBatch > 1000) {
            throw new IllegalArgumentException("Пачка от 1 до 1000");
        }
    }

    /** Значения архитектуры. */
    public static SellerSettings defaults() {
        return new SellerSettings(Duration.ofSeconds(600), Duration.ofMinutes(1), Duration.ofSeconds(30), 20, 20);
    }

    /** Сколько отправок запроса допускает окно: три. */
    public static int maxSends() {
        return PAUSES.length + 1;
    }

    /**
     * Пауза перед следующей отправкой после неудачной отправки с номером {@code attemptNo} (с единицы): 5 секунд после первой, 30 после второй; после
     * последней пусто (запроса ждёт тайм-аут).
     */
    public static Duration pauseAfter(int attemptNo) {
        if (attemptNo < 1) {
            throw new IllegalArgumentException("Номер отправки начинается с 1");
        }
        return attemptNo <= PAUSES.length ? PAUSES[attemptNo - 1] : null;
    }
}
