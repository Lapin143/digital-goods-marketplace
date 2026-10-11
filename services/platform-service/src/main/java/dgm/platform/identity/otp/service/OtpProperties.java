package dgm.platform.identity.otp.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Правила кода, не меняемые администратором (NFT-3.7), префикс {@code dgm.otp}. Лимиты запросов и потолок SMS берутся из параметров
 * платформы ({@code audit_admin.api}, решение Р-10), а не отсюда.
 *
 * @param ttl               срок жизни кода
 * @param attempts          попыток ввода на код
 * @param minResponse       нижняя граница ответа на запрос кода входа: ветка без отправки ждёт этого времени (time-budgets.md, решение 4)
 * @param expiredGrace      сколько запись кода входа остаётся после срока: ответ «устарел» одинаков для настоящего номера и приманки
 * @param burnLimit         сколько погашенных исчерпанием попыток кодов за {@code burnWindow} закрывают номер
 * @param burnWindow        окно подсчёта погашенных кодов
 * @param exhaustedRetryAfter подсказка {@code Retry-After}, когда попытки кода кончились, а номер не закрыт
 */
@ConfigurationProperties(prefix = "dgm.otp")
public record OtpProperties(
        @DefaultValue("5m") Duration ttl,
        @DefaultValue("5") int attempts,
        @DefaultValue("800ms") Duration minResponse,
        @DefaultValue("10m") Duration expiredGrace,
        @DefaultValue("3") int burnLimit,
        @DefaultValue("1h") Duration burnWindow,
        @DefaultValue("60s") Duration exhaustedRetryAfter) {

    public static OtpProperties defaults() {
        return new OtpProperties(Duration.ofMinutes(5), 5, Duration.ofMillis(800), Duration.ofMinutes(10), 3, Duration.ofHours(1),
                Duration.ofSeconds(60));
    }
}
