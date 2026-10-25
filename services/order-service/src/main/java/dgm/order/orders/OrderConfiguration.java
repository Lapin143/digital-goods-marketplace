package dgm.order.orders;

import dgm.kit.boot.PeerClients;
import dgm.kit.config.ConfigRegistry;
import dgm.order.orders.client.CatalogClient;
import dgm.order.orders.client.InventoryClient;
import dgm.order.orders.client.PaymentClient;
import dgm.order.orders.client.PeerCatalogClient;
import dgm.order.orders.client.PeerInventoryClient;
import dgm.order.orders.client.PeerPaymentClient;
import dgm.order.orders.repository.OrderRepository;
import dgm.order.orders.repository.OrderStore;
import dgm.order.orders.service.DeliveryAddressService;
import dgm.order.orders.service.OrderSettings;
import dgm.order.orders.service.OrderTransaction;
import dgm.order.orders.service.OrderTransitions;
import dgm.order.orders.service.OrderViewService;
import dgm.order.orders.service.SagaOrchestrator;
import dgm.order.orders.watchdog.OrderWatchdog;
import dgm.order.orders.watchdog.WatchdogSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля заказов: настройки с проверкой INV-07 (неверные значения останавливают запуск), клиенты соседей и сага покупки. Соседи описаны в
 * {@code dgm.peers.*} (каталог, остатки, платежи), клиенты берутся у каркаса по имени и переиспользуются, поэтому предохранитель у каждого соседа один
 * на весь сервис. Сторож заказов работает по расписанию ({@code dgm.order.watchdog.*}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OrderConfiguration {

    /** Значения из {@code application.yml} проверяются при старте (INV-07), дальше настройки живые: их обновляет слушатель параметров. */
    @Bean
    OrderSettings orderSettings(Environment environment, ConfigRegistry registry) {
        return OrderSettings.live(registry, new OrderSettings(
                environment.getProperty(OrderSettings.MAX_UNPAID_KEY, Integer.class, 3),
                Duration.ofSeconds(environment.getProperty(OrderSettings.RESERVATION_TTL_KEY, Long.class, 900L)),
                Duration.ofSeconds(environment.getProperty(OrderSettings.SESSION_TTL_KEY, Long.class, 720L)),
                environment.getProperty(OrderSettings.COMMISSION_KEY, Integer.class, 200)));
    }

    @Bean
    OrderTransitions orderTransitions(OrderStore store, OrderTransaction transaction, OrderSettings settings, MeterRegistry meters) {
        return new OrderTransitions(store, transaction, settings, meters);
    }

    @Bean
    CatalogClient catalogClient(PeerClients peers) {
        return new PeerCatalogClient(() -> peers.get(PeerCatalogClient.PEER));
    }

    @Bean
    InventoryClient inventoryClient(PeerClients peers) {
        return new PeerInventoryClient(() -> peers.get(PeerInventoryClient.PEER));
    }

    @Bean
    PaymentClient paymentClient(PeerClients peers) {
        return new PeerPaymentClient(() -> peers.get(PeerPaymentClient.PEER));
    }

    @Bean
    SagaOrchestrator sagaOrchestrator(CatalogClient catalog, InventoryClient inventory, PaymentClient payments, OrderTransitions transitions,
                                      OrderSettings settings, Clock clock, MeterRegistry meters) {
        return new SagaOrchestrator(catalog, inventory, payments, transitions, settings, clock, meters);
    }

    @Bean
    WatchdogSettings watchdogSettings(Environment environment) {
        WatchdogSettings defaults = WatchdogSettings.defaults();
        return new WatchdogSettings(
                environment.getProperty("dgm.order.watchdog.created-timeout", Duration.class, defaults.createdTimeout()),
                environment.getProperty("dgm.order.watchdog.reserve-grace", Duration.class, defaults.reserveGrace()),
                environment.getProperty("dgm.order.watchdog.batch", Integer.class, defaults.batch()));
    }

    @Bean
    OrderWatchdog orderWatchdog(OrderRepository orders, SagaOrchestrator saga, WatchdogSettings settings, Clock clock, MeterRegistry meters) {
        return new OrderWatchdog(orders, saga, settings, clock, meters);
    }

    @Bean
    DeliveryAddressService deliveryAddressService(OrderTransitions transitions, Clock clock) {
        return new DeliveryAddressService(transitions, clock);
    }

    @Bean
    OrderViewService orderViewService(OrderRepository orders, Clock clock) {
        return new OrderViewService(orders, clock);
    }
}
