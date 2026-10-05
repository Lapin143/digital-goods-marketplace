package dgm.gateway;

import reactor.core.publisher.Mono;

/** Ограничение частоты запросов по группе маршрутов и ключу (пользователь или IP). */
public interface RequestLimiter {

    /** Проверка одного запроса. Ошибка в потоке значит «ограничитель недоступен»: решение об отказе открытым принимает вызывающий. */
    Mono<Decision> check(String group, String key);

    /**
     * Решение.
     *
     * @param allowed           запрос пропускается
     * @param retryAfterSeconds через сколько секунд повторить (для отказа, не меньше 1)
     * @param failOpen          запрос пропущен без проверки, потому что ограничитель не отвечает
     */
    record Decision(boolean allowed, int retryAfterSeconds, boolean failOpen) {

        public static Decision allow() {
            return new Decision(true, 0, false);
        }

        public static Decision failOpen() {
            return new Decision(true, 0, true);
        }

        public static Decision deny(int retryAfterSeconds) {
            return new Decision(false, Math.max(1, retryAfterSeconds), false);
        }
    }
}
