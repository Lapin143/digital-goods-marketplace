package dgm.payment.payments;

import dgm.kit.boot.PeerClients;
import dgm.kit.boot.SecretsDirectory;
import dgm.payment.payments.gateway.PaymentGateway;
import dgm.payment.payments.gateway.client.HttpPaymentGateway;
import dgm.payment.payments.reconcile.PaymentReconciler;
import dgm.payment.payments.reconcile.PaymentReconciliation;
import dgm.payment.payments.reconcile.ReconcileSettings;
import dgm.payment.payments.reconcile.UnmatchedNotificationJob;
import dgm.payment.payments.repository.PaymentEventRepository;
import dgm.payment.payments.repository.PaymentRepository;
import dgm.payment.payments.repository.UnmatchedNotificationRepository;
import dgm.payment.payments.service.PaymentOutcomes;
import dgm.payment.payments.service.PaymentTransaction;
import dgm.payment.payments.session.PaymentSessionService;
import dgm.payment.payments.session.SessionSettings;
import dgm.payment.payments.webhook.PaymentWebhookService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Подключение модуля платежей: сроки сессии (INV-07 проверяется при создании, неверные значения останавливают запуск), клиент шлюза и
 * сервис открытия сессии. Шлюз описан как сосед {@code payment-gateway} в {@code dgm.peers}; ключ шлюза лежит в секрете
 * {@code payment_gateway_key}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class PaymentConfiguration {

    /** Имя соседа в {@code dgm.peers.*}. */
    static final String GATEWAY_PEER = "payment-gateway";

    /** Тот же шлюз для запроса статуса внутри вебхука: одна попытка и короткий срок, без повторов (ADR-015, шаг 4а). */
    static final String GATEWAY_STATUS_PEER = "payment-gateway-status";

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
        return new HttpPaymentGateway(() -> peers.get(GATEWAY_PEER), () -> peers.get(GATEWAY_STATUS_PEER), () -> secrets.read(GATEWAY_KEY_SECRET));
    }

    @Bean
    ReconcileSettings reconcileSettings(Environment environment) {
        ReconcileSettings defaults = ReconcileSettings.defaults();
        return new ReconcileSettings(
                environment.getProperty("dgm.payment.reconcile.young-age", Duration.class, defaults.youngAge()),
                environment.getProperty("dgm.payment.reconcile.max-age", Duration.class, defaults.maxAge()),
                environment.getProperty("dgm.payment.reconcile.unmatched-alert-after", Duration.class, defaults.alertAfter()),
                environment.getProperty("dgm.payment.reconcile.batch", Integer.class, defaults.batch()));
    }

    @Bean
    PaymentOutcomes paymentOutcomes(PaymentRepository payments, PaymentEventRepository events, PaymentTransaction transaction, Clock clock,
                                    MeterRegistry meters) {
        return new PaymentOutcomes(payments, events, transaction, clock, meters);
    }

    @Bean
    PaymentWebhookService paymentWebhookService(PaymentRepository payments, PaymentEventRepository events, UnmatchedNotificationRepository unmatched,
                                                PaymentGateway gateway, PaymentOutcomes outcomes, Clock clock, MeterRegistry meters) {
        return new PaymentWebhookService(payments, events, unmatched, gateway, outcomes, clock, meters);
    }

    @Bean
    PaymentReconciliation paymentReconciliation(PaymentGateway gateway, PaymentOutcomes outcomes, Clock clock, MeterRegistry meters) {
        return new PaymentReconciliation(gateway, outcomes, clock, meters);
    }

    @Bean
    PaymentReconciler paymentReconciler(PaymentRepository payments, PaymentReconciliation reconciliation, ReconcileSettings settings, Clock clock,
                                        MeterRegistry meters) {
        return new PaymentReconciler(payments, reconciliation, settings, clock, meters);
    }

    @Bean
    UnmatchedNotificationJob unmatchedNotificationJob(UnmatchedNotificationRepository unmatched, PaymentRepository payments,
                                                      PaymentReconciliation reconciliation, PaymentOutcomes outcomes, ReconcileSettings settings, Clock clock,
                                                      MeterRegistry meters) {
        return new UnmatchedNotificationJob(unmatched, payments, reconciliation, outcomes, settings, clock, meters);
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
