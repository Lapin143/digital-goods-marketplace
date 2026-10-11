package dgm.platform.identity.otp.testing;

import dgm.kit.time.ManualClock;
import dgm.platform.identity.otp.crypto.AuditHasher;
import dgm.platform.identity.otp.crypto.OtpCrypto;
import dgm.platform.identity.otp.service.OtpProperties;
import dgm.platform.identity.otp.service.OtpService;
import dgm.platform.identity.otp.service.PhoneConfirmationService;
import dgm.platform.identity.otp.service.SmsLoginService;
import dgm.platform.identity.otp.service.SmsStatusService;
import dgm.platform.identity.testing.FakeIdentityProvider;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.identity.testing.InMemoryAccounts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Всё для одноразовых кодов в памяти: часы, хранилище, SMS, учётные записи, Keycloak и собранные сервисы. Для тестов контроллеров. */
public final class OtpWorld {

    public final ManualClock clock;
    public final InMemoryAccounts accounts = new InMemoryAccounts();
    public final FakeTransaction tx = new FakeTransaction();
    public final FakeIdentityProvider keycloak = new FakeIdentityProvider(tx);
    public final InMemoryOtpStore store;
    public final RecordingSms sms = new RecordingSms();
    public final ScriptedCodes codes = new ScriptedCodes();
    public final ClockedPacer pacer;
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    public final OtpService otp;
    public final PhoneConfirmationService confirmations;
    public final SmsLoginService logins;
    public final SmsStatusService statuses;

    public OtpWorld(Instant start) {
        this.clock = new ManualClock(start);
        this.store = new InMemoryOtpStore(clock);
        this.pacer = new ClockedPacer(clock);
        this.otp = new OtpService(store, OtpCrypto.ofBytes("test-pepper-0123456789abcdef-0123".getBytes(StandardCharsets.UTF_8)), codes, sms,
                new FakeParameters(), OtpProperties.defaults(), clock, meters);
        AuditHasher hasher = AuditHasher.ofBytes("test-audit-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        this.confirmations = new PhoneConfirmationService(accounts, keycloak, tx, otp, hasher, clock);
        this.logins = new SmsLoginService(accounts, tx, otp, pacer, OtpProperties.defaults(), clock);
        this.statuses = new SmsStatusService(store, meters);
    }
}
