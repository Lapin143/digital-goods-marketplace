package dgm.kit.config;

import java.time.Duration;
import java.util.List;

/**
 * Источник сообщений слушателя: записи сжатой темы с начала, по порядку. Отделён от Kafka, чтобы цикл слушателя проверялся без брокера.
 */
public interface ConfigFeed extends AutoCloseable {

    /**
     * Значения записей, пришедших за время ожидания (пустой список, если новых нет). Запись без значения (надгробие) пропускается.
     *
     * @throws RuntimeException источник недоступен: слушатель закроет его и начнёт чтение заново
     */
    List<String> poll(Duration timeout);

    /** Прерывает ожидание в {@link #poll} из другого потока (остановка слушателя). */
    void wakeup();

    @Override
    void close();
}
