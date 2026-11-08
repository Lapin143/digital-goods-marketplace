package dgm.keycloak.smsotp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Диалог «адрес, затем код из письма» (Р-194, F4-3): шаги без Keycloak, платформа замещена двойником. */
class VkEmailLoginTest {

    private static final String EMAIL = "guest@mail.example";

    /** Платформа-двойник: запоминает вызовы и отвечает заданным. */
    private static final class FakePlatform implements PlatformOtp {
        Issued issued = new Issued.Accepted();
        EmailChecked checked = new EmailChecked.Invalid();
        final List<String> calls = new ArrayList<>();

        @Override
        public Issued issue(String phone, String clientIp) {
            throw new AssertionError("вход через VK ID не запрашивает SMS");
        }

        @Override
        public Verified verify(String phone, String code) {
            throw new AssertionError("вход через VK ID не проверяет коды SMS");
        }

        @Override
        public Issued issueEmail(String email) {
            calls.add("issueEmail " + email);
            return issued;
        }

        @Override
        public EmailChecked verifyEmail(String email, String code) {
            calls.add("verifyEmail " + email + " " + code);
            return checked;
        }

        @Override
        public Quota consumeQuota(String purpose, String email) {
            throw new AssertionError("счётчик для vk_email занимает платформа сама");
        }
    }

    private final FakePlatform platform = new FakePlatform();
    private final Set<String> existing = new HashSet<>();
    private final List<String> lookups = new ArrayList<>();
    private final VkEmailLogin login = new VkEmailLogin(platform, email -> {
        lookups.add(email);
        return existing.contains(email);
    });

    @Tag("TC-140")
    @Test
    void validAddressLeadsToCodePageWithoutMessageAndIsLowerCased() {
        assertEquals(new VkEmailLogin.Step.AskCode(EMAIL, null), login.submitEmail("  Guest@Mail.Example "));
        assertEquals(List.of("issueEmail " + EMAIL), platform.calls);
    }

    @Tag("TC-142")
    @Test
    void takenAndFreeAddressesGiveIdenticalStepsAndCallsBeforeTheCode() {
        existing.add("taken@mail.example");

        VkEmailLogin.Step taken = login.submitEmail("taken@mail.example");
        VkEmailLogin.Step free = login.submitEmail("free@mail.example");

        assertEquals(VkEmailLogin.Step.AskCode.class, taken.getClass());
        assertEquals(VkEmailLogin.Step.AskCode.class, free.getClass());
        assertEquals(((VkEmailLogin.Step.AskCode) taken).message(), ((VkEmailLogin.Step.AskCode) free).message());
        assertTrue(lookups.isEmpty(), "до верного кода занятость адреса не проверяется: по ответу и времени её не различить (ST-06)");
    }

    @Tag("TC-140")
    @Test
    void invalidAddressNeverReachesPlatform() {
        for (String bad : new String[] {null, "", "no-at-sign", "a@b", "a b@mail.example", "a@@mail.example", "x".repeat(250) + "@m.example"}) {
            assertEquals(new VkEmailLogin.Step.AskEmail(VkEmailLogin.Message.EMAIL_INVALID), login.submitEmail(bad), String.valueOf(bad));
        }
        assertTrue(platform.calls.isEmpty());
    }

    @Tag("TC-143")
    @Test
    void requestLimitReturnsToAddressPageWithWait() {
        platform.issued = new PlatformOtp.Issued.Limited(125);
        VkEmailLogin.Step.AskEmail ask = assertInstanceOf(VkEmailLogin.Step.AskEmail.class, login.submitEmail(EMAIL));
        assertEquals("vkEmailLimited", ask.message().key());
        assertEquals(3, ask.message().minutes());
    }

    @Tag("TC-143")
    @Test
    void unavailablePlatformIsShownAsUnavailableNotAsWrongAddress() {
        platform.issued = new PlatformOtp.Issued.Unavailable();
        assertEquals(new VkEmailLogin.Step.AskEmail(VkEmailLogin.Message.UNAVAILABLE), login.submitEmail(EMAIL));
    }

    @Tag("TC-143")
    @Test
    void resendKeepsAddressAndShowsLimitOrUnavailable() {
        assertEquals(new VkEmailLogin.Step.AskCode(EMAIL, null), login.resend(EMAIL));
        platform.issued = new PlatformOtp.Issued.Limited(30);
        VkEmailLogin.Step.AskCode limited = assertInstanceOf(VkEmailLogin.Step.AskCode.class, login.resend(EMAIL));
        assertEquals(EMAIL, limited.email());
        assertEquals("vkEmailLimited", limited.message().key());
        assertEquals(1, limited.message().minutes());
        platform.issued = new PlatformOtp.Issued.Unavailable();
        assertEquals(new VkEmailLogin.Step.AskCode(EMAIL, VkEmailLogin.Message.UNAVAILABLE), login.resend(EMAIL));
    }

    @Tag("TC-140")
    @Test
    void correctCodeForAFreeAddressFinishesTheStep() {
        platform.checked = new PlatformOtp.EmailChecked.Confirmed();
        assertEquals(new VkEmailLogin.Step.Done(EMAIL), login.submitCode(EMAIL, "042917"));
        assertEquals(List.of(EMAIL), lookups, "занятость проверяется один раз и только после верного кода");
    }

    @Tag("TC-142")
    @Test
    void correctCodeForATakenAddressDeniesWithoutLinking() {
        existing.add(EMAIL);
        platform.checked = new PlatformOtp.EmailChecked.Confirmed();
        assertEquals(new VkEmailLogin.Step.Denied(), login.submitCode(EMAIL, "042917"));
    }

    @Tag("TC-141")
    @Tag("TC-142")
    @Test
    void wrongCodeNeverChecksTheAddress() {
        existing.add(EMAIL);
        VkEmailLogin.Step.AskCode ask = assertInstanceOf(VkEmailLogin.Step.AskCode.class, login.submitCode(EMAIL, "000000"));
        assertEquals(VkEmailLogin.Message.CODE_INVALID, ask.message());
        assertTrue(lookups.isEmpty(), "по неверному коду о занятости адреса не узнать");
    }

    @Tag("TC-141")
    @Test
    void malformedCodeIsRejectedLocallyAndCostsNoAttempt() {
        for (String bad : new String[] {null, "", "12345", "1234567", "12a456"}) {
            assertEquals(new VkEmailLogin.Step.AskCode(EMAIL, VkEmailLogin.Message.CODE_FORMAT), login.submitCode(EMAIL, bad), String.valueOf(bad));
        }
        assertTrue(platform.calls.isEmpty());
    }

    @Tag("TC-140")
    @Test
    void codeWithSpacesIsNormalised() {
        platform.checked = new PlatformOtp.EmailChecked.Confirmed();
        assertEquals(new VkEmailLogin.Step.Done(EMAIL), login.submitCode(EMAIL, "042 917"));
        assertEquals(List.of("verifyEmail " + EMAIL + " 042917"), platform.calls);
    }

    @Tag("TC-141")
    @Test
    void expiredLockedAndUnavailableGoWhereThePlatformSays() {
        platform.checked = new PlatformOtp.EmailChecked.Expired();
        assertEquals(new VkEmailLogin.Step.AskEmail(VkEmailLogin.Message.CODE_EXPIRED), login.submitCode(EMAIL, "042917"));

        platform.checked = new PlatformOtp.EmailChecked.Locked(600);
        VkEmailLogin.Step.AskEmail locked = assertInstanceOf(VkEmailLogin.Step.AskEmail.class, login.submitCode(EMAIL, "042917"));
        assertEquals("vkEmailLocked", locked.message().key());
        assertEquals(10, locked.message().minutes());

        platform.checked = new PlatformOtp.EmailChecked.Unavailable();
        assertEquals(new VkEmailLogin.Step.AskCode(EMAIL, VkEmailLogin.Message.UNAVAILABLE), login.submitCode(EMAIL, "042917"));
        assertTrue(lookups.isEmpty());
    }
}
