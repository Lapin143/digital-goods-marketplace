package dgm.delivery.delivery.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Настройки выдачи: аренда выдачи диспетчером, размер пачки и окно контроля доставки (параметры платформы, Р-139). Ключи: аренда и пачка
 * {@code dgm.delivery.dispatcher.lease} и {@code dgm.delivery.dispatcher.batch}, окно {@code delivery.control-minutes}.
 *
 * @param lease         аренда: через столько упавший процесс теряет выдачу (две минуты, c4-components-delivery-service.md)
 * @param batch         сколько выдач диспетчер берёт за проход (20)
 * @param controlWindow окно контроля от оплаты (30 минут, NFT-2.4); в {@code fast-time} 3 минуты
 */
public record DeliverySettings(Duration lease, int batch, Duration controlWindow) {

    public DeliverySettings {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(controlWindow, "controlWindow");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("Аренда выдачи должна быть больше нуля");
        }
        if (batch < 1 || batch > 1000) {
            throw new IllegalArgumentException("Пачка выдач от 1 до 1000");
        }
        if (controlWindow.isZero() || controlWindow.isNegative()) {
            throw new IllegalArgumentException("Окно контроля должно быть больше нуля");
        }
    }

    /** Значения архитектуры. */
    public static DeliverySettings defaults() {
        return new DeliverySettings(Duration.ofMinutes(2), 20, Duration.ofMinutes(30));
    }
}
