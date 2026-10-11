package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmsLoginTest {

    /** Платформа-двойник: запоминает вызовы и отвечает заданным. */
    private static final class FakePlatform implements PlatformOtp {
        Issued issued = new Issued.Accepted();
        Verified verified = new Verified.Invalid();
        final List<String> calls = new ArrayList<>();

        @Override
        public Issued issue(String phone, String clientIp) {
            calls.add("issue " + phone + " " + clientIp);
            return issued;
        }

        @Override
        public Verified verify(String phone, String code) {
            calls.add("verify " + phone + " " + code);
            return verified;
        }
    }

    private final FakePlatform platform = new FakePlatform();
    private final SmsLogin login = new SmsLogin(platform);

    @Test
    void anyValidPhoneLeadsToCodePageWithoutMessage() {
        SmsLogin.Step step = login.submitPhone("8 (900) 123-45-67", "203.0.113.5");
        assertEquals(new SmsLogin.Step.AskCode("+79001234567", null), step);
        assertEquals(List.of("issue +79001234567 203.0.113.5"), platform.calls);
    }

    @Test
    void boundAndUnknownNumbersGiveIdenticalSteps() {
        // Платформа отвечает на оба номера одинаково (202): значит, одинаковы и шаги, различать покупателю и атакующему нечего
        SmsLogin.Step bound = login.submitPhone("+79001234567", null);
        SmsLogin.Step unknown = login.submitPhone("+79997654321", null);
        assertEquals(SmsLogin.Step.AskCode.class, bound.getClass());
        assertEquals(SmsLogin.Step.AskCode.class, unknown.getClass());
        assertEquals(((SmsLogin.Step.AskCode) bound).message(), ((SmsLogin.Step.AskCode) unknown).message());
    }

    @Test
    void invalidPhoneNeverReachesPlatform() {
        assertEquals(new SmsLogin.Step.AskPhone(SmsLogin.Message.PHONE_INVALID), login.submitPhone("123", "203.0.113.5"));
        assertTrue(platform.calls.isEmpty());
    }

    @Test
    void requestLimitReturnsToPhonePageWithWait() {
        platform.issued = new PlatformOtp.Issued.Limited(125);
        SmsLogin.Step step = login.submitPhone("+79001234567", null);
        SmsLogin.Step.AskPhone ask = assertInstanceOf(SmsLogin.Step.AskPhone.class, step);
        assertEquals("smsLimited", ask.message().key());
        assertEquals(3, ask.message().minutes());
    }

    @Test
    void unavailablePlatformIsShownAsUnavailableNotAsWrongNumber() {
        platform.issued = new PlatformOtp.Issued.Unavailable();
        assertEquals(new SmsLogin.Step.AskPhone(SmsLogin.Message.UNAVAILABLE), login.submitPhone("+79001234567", null));
    }

    @Test
    void resendKeepsPhoneAndShowsLimit() {
        assertEquals(new SmsLogin.Step.AskCode("+79001234567", null), login.resend("+79001234567", null));
        platform.issued = new PlatformOtp.Issued.Limited(30);
        SmsLogin.Step.AskCode ask = assertInstanceOf(SmsLogin.Step.AskCode.class, login.resend("+79001234567", null));
        assertEquals("+79001234567", ask.phone());
        assertEquals("smsLimited", ask.message().key());
        assertEquals(1, ask.message().minutes());
    }

    @Test
    void codeOfWrongFormatIsRejectedLocallyAndSpendsNoAttempt() {
        assertEquals(new SmsLogin.Step.AskCode("+79001234567", SmsLogin.Message.CODE_FORMAT), login.submitCode("+79001234567", "12ab"));
        assertTrue(platform.calls.isEmpty());
    }

    @Test
    void verifiedCodeFinishesWithUserId() {
        platform.verified = new PlatformOtp.Verified.Accepted("120fcee1-8c5f-4b89-8a1c-4c02a9375eb9");
        assertEquals(new SmsLogin.Step.Done("120fcee1-8c5f-4b89-8a1c-4c02a9375eb9"), login.submitCode("+79001234567", " 123 456 "));
        assertEquals(List.of("verify +79001234567 123456"), platform.calls);
    }

    @Test
    void wrongCodeStaysOnCodePage() {
        platform.verified = new PlatformOtp.Verified.Invalid();
        assertEquals(new SmsLogin.Step.AskCode("+79001234567", SmsLogin.Message.CODE_INVALID), login.submitCode("+79001234567", "123456"));
    }

    @Test
    void expiredCodeReturnsToPhonePage() {
        platform.verified = new PlatformOtp.Verified.Expired();
        assertEquals(new SmsLogin.Step.AskPhone(SmsLogin.Message.CODE_EXPIRED), login.submitCode("+79001234567", "123456"));
    }

    @Test
    void lockedNumberReturnsToPhonePageWithWait() {
        platform.verified = new PlatformOtp.Verified.Locked(900);
        SmsLogin.Step.AskPhone ask = assertInstanceOf(SmsLogin.Step.AskPhone.class, login.submitCode("+79001234567", "123456"));
        assertEquals("smsLocked", ask.message().key());
        assertEquals(15, ask.message().minutes());
    }

    @Test
    void unavailablePlatformOnVerifyKeepsCodePage() {
        platform.verified = new PlatformOtp.Verified.Unavailable();
        assertEquals(new SmsLogin.Step.AskCode("+79001234567", SmsLogin.Message.UNAVAILABLE), login.submitCode("+79001234567", "123456"));
    }

    @Test
    void waitIsRoundedUpToWholeMinutes() {
        assertEquals(1, SmsLogin.Message.limited(1).minutes());
        assertEquals(1, SmsLogin.Message.limited(60).minutes());
        assertEquals(2, SmsLogin.Message.limited(61).minutes());
        assertEquals(1, SmsLogin.Message.UNAVAILABLE.minutes());
    }
}
