package dgm.platform.support.testing;

import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.service.EmailChangeCodeService;
import dgm.platform.identity.otp.service.OtpProperties;
import dgm.platform.identity.otp.service.OtpService;
import dgm.platform.identity.otp.testing.FakeParameters;
import dgm.platform.identity.otp.testing.InMemoryOtpStore;
import dgm.platform.identity.otp.testing.RecordingMail;
import dgm.platform.identity.otp.testing.RecordingSms;
import dgm.platform.identity.otp.testing.ScriptedCodes;
import dgm.platform.identity.otp.crypto.AuditHasher;
import dgm.platform.identity.service.AccountContacts;
import dgm.platform.identity.service.AccountEmailChanger;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import dgm.platform.notification.testing.FakeContacts;
import dgm.platform.support.service.EmailChangeService;
import dgm.platform.support.service.QueueMetrics;
import dgm.platform.support.service.QueueService;
import dgm.platform.support.service.ResendService;
import dgm.platform.support.service.SupportService;
import dgm.platform.support.service.TicketService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Модуль поддержки в памяти для тестов обращений покупателя: модель чтения, транзакция с откатом, Outbox, лимит, параметры, часы и метрики. */
public final class SupportWorld {

    public static final UUID BUYER = UUID.fromString("3f8e1d52-7b6a-4c90-8d21-5a9b0e7c4f13");
    public static final UUID STRANGER = UUID.fromString("7a1c9e04-2d3b-4f58-9b6e-8c0d1a2b3c4d");
    public static final UUID OPERATOR = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");
    public static final UUID OPERATOR_2 = UUID.fromString("5d4c3b2a-1f0e-4d9c-8b7a-6e5f4d3c2b1a");
    public static final Instant T0 = Instant.parse("2026-10-25T12:00:00Z");

    public final ManualClock clock = new ManualClock(T0);
    public final InMemorySupport repository = new InMemorySupport();
    public final RecordingOutbox outbox = new RecordingOutbox();
    public final InMemoryRateLimiter limiter = new InMemoryRateLimiter();
    public final FakeParameters parameters = new FakeParameters();
    public final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    public final SupportService events = new SupportService(repository, outbox, clock);
    public final TicketService tickets = new TicketService(repository, new FakeSupportTransaction(repository), outbox, limiter, parameters, clock, metrics);
    public final QueueService queue = new QueueService(repository, new FakeSupportTransaction(repository), outbox, clock, metrics);
    public final QueueMetrics queueMetrics = new QueueMetrics(repository, clock, metrics);
    public final FakeContacts contacts = new FakeContacts();
    public final FakeOrders orders = new FakeOrders();
    public final ResendService resend = new ResendService(repository, new FakeSupportTransaction(repository), outbox, contacts, orders, clock, metrics);

    public static final String BUYER_EMAIL = "buyer@example.test";
    public static final String BUYER_PHONE = "+79001234567";

    // Смена e-mail (шаг 14): настоящие службы кодов и лимитов на хранилище в памяти и общих часах, SMS и письма записываются в фейках
    public final InMemoryAccounts accounts = new InMemoryAccounts();
    public final InMemoryOtpStore otpStore = new InMemoryOtpStore(clock);
    public final RecordingSms sms = new RecordingSms();
    public final RecordingMail mail = new RecordingMail();
    public final ScriptedCodes scriptedCodes = new ScriptedCodes();
    public final OtpService otp = new OtpService(otpStore, OtpCrypto.ofBytes("test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8)),
            scriptedCodes, sms, parameters, OtpProperties.defaults(), clock, metrics);
    public final EmailChangeCodeService codes = new EmailChangeCodeService(accounts, otp, sms, mail);
    // Завершение смены (шаг 15): настоящий AccountEmailChanger на Keycloak и транзакции identity в памяти; повторная отправка ключа берёт адрес из
    // той же учётной записи, которую меняет смена
    public final FakeTransaction identityTransaction = new FakeTransaction();
    public final FakeIdentityProvider keycloak = new FakeIdentityProvider(identityTransaction);
    public final AccountContacts accountContacts = new AccountContacts(accounts, clock);
    public final AccountEmailChanger emailChanger = new AccountEmailChanger(accounts, keycloak, identityTransaction, AuditHasher.ofBytes(new byte[32]), clock);
    public final ResendService accountResend = new ResendService(repository, new FakeSupportTransaction(repository), outbox, accountContacts, orders, clock,
            metrics);
    public final EmailChangeService emailChange = new EmailChangeService(repository, repository, new FakeSupportTransaction(repository), outbox, codes,
            accountContacts, contacts, emailChanger, accountResend, clock, metrics);

    private long nextNumber = 1000;

    public SupportWorld() {
        contacts.put(BUYER, BUYER_EMAIL, "Покупатель");
        accounts.add(BUYER, BUYER_EMAIL, "buyer", T0);
        accounts.givePhone(BUYER, BUYER_PHONE);
        keycloak.add(FakeIdentityProvider.user(BUYER, BUYER_EMAIL, "Покупатель", "Тестовый", true), "buyer");
    }

    /** Ещё один покупатель с подтверждённым телефоном: учётная запись платформы, пользователь Keycloak и контакт для писем. */
    public void addBuyer(UUID id, String email, String phone) {
        accounts.add(id, email, "buyer", T0);
        accounts.givePhone(id, phone);
        contacts.put(id, email, "Покупатель");
        keycloak.add(FakeIdentityProvider.user(id, email, "Покупатель", "Тестовый", true), "buyer");
    }

    /** Выданный заказ покупателя: события {@code order.paid} и {@code order.issued} применяет служба поддержки, как потребитель. */
    public UUID issuedOrder(UUID buyer, Instant issuedAt) {
        UUID orderId = UUID.randomUUID();
        long number = nextNumber++;
        events.onOrderPaid(orderId, number, buyer);
        events.onOrderIssued(orderId, number, buyer, issuedAt);
        return orderId;
    }

    /** Оплаченный, но ещё не выданный заказ. */
    public UUID paidOrder(UUID buyer) {
        UUID orderId = UUID.randomUUID();
        events.onOrderPaid(orderId, nextNumber++, buyer);
        return orderId;
    }

    /** Обращение системы по заказу: событие {@code delivery.overdue} создаёт его в статусе «создано». */
    public UUID overdueTicketOf(UUID orderId) {
        events.onDeliveryOverdue(orderId, clock.instant());
        return repository.ticketsOf(orderId).get(0).id;
    }

    public double count(String result) {
        var counter = metrics.find(TicketService.METRIC_NAME).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
