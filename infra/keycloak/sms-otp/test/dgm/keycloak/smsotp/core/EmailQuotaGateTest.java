package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Решение «отправлять ли письмо» по счётчику платформы (F4-1): регистрация и сброс пароля. */
@Tag("TC-143")
class EmailQuotaGateTest {

    private static final class FakePlatform implements PlatformOtp {
        Quota quota = new Quota.Granted();
        final List<String> calls = new ArrayList<>();

        @Override
        public Issued issue(String phone, String clientIp) {
            throw new AssertionError("не используется");
        }

        @Override
        public Verified verify(String phone, String code) {
            throw new AssertionError("не используется");
        }

        @Override
        public Issued issueEmail(String email) {
            throw new AssertionError("не используется");
        }

        @Override
        public EmailChecked verifyEmail(String email, String code) {
            throw new AssertionError("не используется");
        }

        @Override
        public Quota consumeQuota(String purpose, String email) {
            calls.add(purpose + " " + email);
            return quota;
        }
    }

    private final FakePlatform platform = new FakePlatform();
    private final EmailQuotaGate gate = new EmailQuotaGate(platform);

    @Test
    void grantedQuotaMeansSendAndTheAddressGoesLowerCased() {
        assertEquals(EmailQuotaGate.Verdict.SEND, gate.check("register", "  Buyer@Mail.Example "));
        assertEquals(List.of("register buyer@mail.example"), platform.calls);
    }

    @Test
    void limitMeansTheLetterIsWithheld() {
        platform.quota = new PlatformOtp.Quota.Limited(300);
        assertEquals(EmailQuotaGate.Verdict.WITHHOLD_LIMITED, gate.check("reset", "buyer@mail.example"));
    }

    @Test
    void unavailablePlatformMeansTheLetterIsWithheld() {
        platform.quota = new PlatformOtp.Quota.Unavailable();
        assertEquals(EmailQuotaGate.Verdict.WITHHOLD_UNAVAILABLE, gate.check("register", "buyer@mail.example"));
    }

    @Test
    void anAddressThePlatformWouldRefuseGetsNoLetterAndNoCall() {
        for (String bad : new String[] {null, "", "not-an-address", "x".repeat(250) + "@m.example"}) {
            assertEquals(EmailQuotaGate.Verdict.WITHHOLD_UNAVAILABLE, gate.check("register", bad), String.valueOf(bad));
        }
        assertTrue(platform.calls.isEmpty());
    }
}
