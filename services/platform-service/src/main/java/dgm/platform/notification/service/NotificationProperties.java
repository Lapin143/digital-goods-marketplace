package dgm.platform.notification.service;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки отправки уведомлений, префикс {@code dgm.notification}. Паузу между просмотрами очереди ({@code dgm.notification.poll-interval},
 * по умолчанию {@code PT0.5S}, лестница повторов начинается с секунды) читает расписание отправителя.
 *
 * @param lease        аренда уведомления, взятого в работу: если экземпляр упал во время отправки, уведомление вернётся в очередь по её истечении
 * @param batch        сколько уведомлений отправляется за один просмотр очереди
 * @param retryDelays  паузы повторов после первой, второй, третьей неудачи (ADR-011: 1, 5, 25 секунд)
 * @param zone         часовой пояс времени в письмах
 * @param zoneLabel    подпись пояса после времени
 */
@ConfigurationProperties(prefix = "dgm.notification")
public record NotificationProperties(
        @DefaultValue("30s") Duration lease,
        @DefaultValue("10") int batch,
        @DefaultValue({"1s", "5s", "25s"}) List<Duration> retryDelays,
        @DefaultValue("Europe/Moscow") String zone,
        @DefaultValue("МСК") String zoneLabel) {
}
