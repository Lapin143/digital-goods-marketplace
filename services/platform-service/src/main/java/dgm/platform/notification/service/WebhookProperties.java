package dgm.platform.notification.service;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки отправки webhook продавцам, префикс {@code dgm.notification.webhook} (Р-193). Паузу между просмотрами очереди
 * ({@code dgm.notification.webhook.interval}, по умолчанию {@code PT1S}) читает расписание отправителя.
 *
 * @param lease       аренда уведомления, взятого в работу: если экземпляр упал во время отправки, уведомление вернётся в очередь по её истечении;
 *                    пачка из 10 уведомлений при тайм-ауте вызова 5 секунд укладывается в минуту, поэтому аренда в две минуты не истекает посреди прохода
 * @param batch       сколько уведомлений берётся за один просмотр очереди
 * @param retryDelays паузы после первой, второй, третьей, четвёртой и пятой неудачи: 30 секунд, 2, 10, 30 минут, 2 часа; первая попытка идёт сразу,
 *                    после шестой неудачи уведомление получает статус {@code failed} (Р-193)
 */
@ConfigurationProperties(prefix = "dgm.notification.webhook")
public record WebhookProperties(
        @DefaultValue("2m") Duration lease,
        @DefaultValue("10") int batch,
        @DefaultValue({"30s", "2m", "10m", "30m", "2h"}) List<Duration> retryDelays) {
}
