package dgm.inventory.inventory.reservation;

import dgm.inventory.inventory.reservation.domain.ReservationSettings;
import dgm.inventory.inventory.reservation.service.ExpiryQueue;
import dgm.inventory.inventory.reservation.timer.NoExpiryQueue;
import dgm.inventory.inventory.reservation.timer.RedisExpiryQueue;
import dgm.kit.config.ConfigRegistry;
import dgm.kit.redis.KitRedis;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля резервов: настройки срока и очередь сроков в Redis. Ключ срока резерва совпадает с ключом параметра платформы
 * (Р-10); значение вне допустимых границ останавливает запуск сервиса. Исполнители (таймер, сверка) работают по расписанию и
 * включаются отсюда же.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ReservationConfiguration {

    /**
     * Срок резерва {@code reservation.ttl-seconds} и допуск сверки {@code dgm.reservation.reconcile-grace-seconds}; не заданы: значения по
     * умолчанию. Срок резерва живой: его обновляет слушатель параметров платформы.
     */
    @Bean
    ReservationSettings reservationSettings(Environment environment, ConfigRegistry registry) {
        ReservationSettings defaults = ReservationSettings.defaults();
        Long seconds = environment.getProperty(ReservationSettings.TTL_KEY, Long.class);
        Long graceSeconds = environment.getProperty(ReservationSettings.GRACE_KEY, Long.class);
        return ReservationSettings.live(registry, new ReservationSettings(seconds == null ? defaults.ttl() : Duration.ofSeconds(seconds),
                graceSeconds == null ? defaults.reconcileGrace() : Duration.ofSeconds(graceSeconds)));
    }

    /** Очередь сроков на Redis каркаса; без {@code dgm.redis.host} очереди нет, резервы снимает сверка по базе. */
    @Bean
    ExpiryQueue expiryQueue(ObjectProvider<KitRedis> redis) {
        KitRedis client = redis.getIfAvailable();
        return client == null ? new NoExpiryQueue() : new RedisExpiryQueue(client);
    }
}
