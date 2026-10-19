package dgm.order.orders;

import dgm.kit.boot.PeerClients;
import dgm.order.orders.client.CatalogClient;
import dgm.order.orders.client.InventoryClient;
import dgm.order.orders.client.PaymentClient;
import dgm.order.orders.client.PeerCatalogClient;
import dgm.order.orders.client.PeerInventoryClient;
import dgm.order.orders.client.PeerPaymentClient;
import dgm.order.orders.repository.OrderRepository;
import dgm.order.orders.repository.OrderStore;
import dgm.order.orders.service.OrderSettings;
import dgm.order.orders.service.OrderTransaction;
import dgm.order.orders.service.OrderTransitions;
import dgm.order.orders.service.OrderViewService;
import dgm.order.orders.service.SagaOrchestrator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Подключение модуля заказов: настройки с проверкой INV-07 (неверные значения останавливают запуск), клиенты соседей и сага покупки. Соседи описаны в
 * {@code dgm.peers.*} (каталог, остатки, платежи), клиенты берутся у каркаса по имени и переиспользуются, поэтому предохранитель у каждого соседа один
 * на весь сервис.
 */
@Configuration(proxyBeanMethods = false)
public class OrderConfiguration {

    @Bean
    OrderSettings orderSettings(Environment environment) {
        return new OrderSettings(
                environment.getProperty(OrderSettings.MAX_UNPAID_KEY, Integer.class, 3),
                Duration.ofSeconds(environment.getProperty(OrderSettings.RESERVATION_TTL_KEY, Long.class, 900L)),
                Duration.ofSeconds(environment.getProperty(OrderSettings.SESSION_TTL_KEY, Long.class, 720L)),
                environment.getProperty(OrderSettings.COMMISSION_KEY, Integer.class, 200));
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
    OrderViewService orderViewService(OrderRepository orders, Clock clock) {
        return new OrderViewService(orders, clock);
    }
}
