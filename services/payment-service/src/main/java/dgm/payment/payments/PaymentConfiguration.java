package dgm.payment.payments;

import dgm.kit.boot.PeerClients;
import dgm.kit.boot.SecretsDirectory;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.gateway.client.HttpPaymentGateway;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.service.PaymentTransaction;
import dgm.payment.payments.session.PaymentSessionService;
import dgm.payment.payments.session.SessionSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Подключение модуля платежей: сроки сессии (INV-07 проверяется при создании, неверные значения останавливают запуск), клиент шлюза и
 * сервис открытия сессии. Шлюз описан как сосед {@code payment-gateway} в {@code dgm.peers}; ключ шлюза лежит в секрете
 * {@code payment_gateway_key}.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentConfiguration {

    /** Имя соседа в {@code dgm.peers.*}. */
    static final String GATEWAY_PEER = "payment-gateway";

    /** Секрет с ключом шлюза. */
    static final String GATEWAY_KEY_SECRET = "payment_gateway_key";

    @Bean
    SessionSettings sessionSettings(Environment environment) {
        return new SessionSettings(
                Duration.ofSeconds(seconds(environment, SessionSettings.RESERVATION_TTL_KEY, 900)),
                Duration.ofSeconds(seconds(environment, SessionSettings.SESSION_TTL_KEY, 720)),
                Duration.ofSeconds(seconds(environment, SessionSettings.MARGIN_KEY, 60)),
                environment.getProperty(SessionSettings.PUBLIC_ORIGIN_KEY, "https://localhost:8443"));
    }

    @Bean
    PaymentGateway paymentGateway(PeerClients peers, SecretsDirectory secrets) {
        return new HttpPaymentGateway(() -> peers.get(GATEWAY_PEER), () -> secrets.read(GATEWAY_KEY_SECRET));
    }

    @Bean
    PaymentSessionService paymentSessionService(PaymentRepository payments, PaymentTransaction transaction, PaymentGateway gateway,
                                                SessionSettings settings, Clock clock, MeterRegistry meters) {
        return new PaymentSessionService(payments, transaction, gateway, settings, clock, meters);
    }

    private static long seconds(Environment environment, String key, long fallback) {
        Long value = environment.getProperty(key, Long.class);
        return value == null ? fallback : value;
    }
}
