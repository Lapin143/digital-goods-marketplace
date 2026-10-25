package dgm.platform.support.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Ограничение частоты создания обращений одним покупателем (параметр {@code support.requests-per-hour}, Р-177). Реализация на Redis
 * ({@code RedisSupportRateLimiter}) считает скользящее окно в один час; тестовая в памяти повторяет её правила. Недоступность хранилища
 * превращается в 503 на месте: без счётчика обращение не создаётся.
 */
public interface SupportRateLimiter {

    /**
     * Допускает создание обращения и записывает его в окно покупателя.
     *
     * @param limit сколько обращений допускается в окне
     * @return пусто: допущено; иначе через сколько освободится место в окне (отказ в окно ничего не записывает)
     */
    Optional<Duration> admit(UUID buyerId, int limit, Instant now);
}
