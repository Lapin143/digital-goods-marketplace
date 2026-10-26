package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.BUYER_EMAIL;
import static dgm.platform.support.testing.SupportWorld.BUYER_PHONE;
import static dgm.platform.support.testing.SupportWorld.OPERATOR;
import static dgm.platform.support.testing.SupportWorld.OPERATOR_2;
import static dgm.platform.support.testing.SupportWorld.STRANGER;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.support.domain.EmailChange;
import dgm.platform.support.domain.EmailChangeView;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.domain.StaffTicket;
import dgm.platform.support.service.EmailChangeService.Step;
import dgm.platform.support.testing.RecordingOutbox;
import dgm.platform.support.testing.SupportWorld;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Смена e-mail по обращению, часть 1 (US-7.4, Р-169): запуск оператором, SMS на подтверждённый телефон, письмо с кодом на новый адрес, этапы,
 * неверные и просроченные коды, пять попыток, лимиты запросов на управляемых часах, недоступный Redis и провайдер, занятый адрес и порядок
 * этапов. Коды ведут настоящие службы на хранилище в памяти: время двигают часы теста, ничего не ждём (NFT-3.7). В записи аудита, метриках и
 * ответах нет ни кодов, ни адресов.
 */
@Tag("TC-711")
@Tag("TC-712")
@Tag("TC-713")
@Tag("TC-714")
@Tag("TC-715")
class EmailChangeServiceTest {

    private static final Operator FIRST = new Operator(OPERATOR, "203.0.113.7");
    private static final Operator SECOND = new Operator(OPERATOR_2, "203.0.113.8");
    private static final String NEW_EMAIL = "new.address@mail.example";
    private static final Optional<String> NO_IP = Optional.empty();

    private SupportWorld world;
    private UUID ticket;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        UUID order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
        ticket = world.tickets.create(BUYER, order).id();
        world.queue.take(FIRST, ticket);
        world.scriptedCodes.then("111111", "222222", "333333", "444444");
    }

    private static ProblemException refused(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    /** Оба кода верны, но Keycloak не отвечает: смена остаётся в точке «адрес подтверждён, не завершён», пока её не доведёт сверка. */
    private void confirmWhileKeycloakIsDown() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        world.keycloak.down(true);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222");
    }

    private List<RecordingOutbox.Event> startRecords() {
        return world.outbox.events("audit.recorded").stream().filter(e -> EmailChangeService.ACTION_STARTED.equals(e.data().get("action"))).toList();
    }

    private EmailChange change() {
        return world.repository.changeOf(ticket).orElseThrow();
    }

    /** Покупатель с подтверждённым телефоном и обращением в работе у оператора {@link #FIRST}. */
    private record OtherBuyer(UUID id, UUID ticket) {
    }

    private OtherBuyer otherBuyer(int index) {
        UUID buyer = UUID.randomUUID();
        String email = "buyer" + index + "@example.test";
        world.accounts.add(buyer, email, "buyer", T0);
        world.accounts.givePhone(buyer, String.format(Locale.ROOT, "+7900000%04d", index));
        world.contacts.put(buyer, email, "Покупатель " + index);
        UUID order = world.issuedOrder(buyer, T0.minus(Duration.ofHours(1)));
        UUID id = world.tickets.create(buyer, order).id();
        world.queue.take(FIRST, id);
        return new OtherBuyer(buyer, id);
    }

    // ---- запуск оператором ----

    @Test
    void startRecordsTheStageThenTheDecisionAndSendsTheSmsToTheConfirmedPhone() {
        EmailChangeView view = world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        assertEquals("awaiting_phone_code", view.status());
        assertEquals("pending", view.phoneCheck());
        assertEquals("not_started", view.newEmailCheck());
        assertEquals("n***@mail.example", view.newEmailMasked());
        assertEquals(Optional.of(NEW_EMAIL), change().newEmail(), "до завершения адрес лежит в записи этапа");
        assertEquals("email_change", world.repository.tickets().get(0).action, "обращение помечено действием смены e-mail");
        assertEquals("in_progress", world.repository.tickets().get(0).status);
        assertEquals(1, world.sms.sent().size());
        assertEquals(BUYER_PHONE, world.sms.sent().get(0).to());
        assertEquals("111111", world.sms.sent().get(0).code());
        assertTrue(world.mail.sent().isEmpty(), "письмо уходит только после проверки телефона");
        assertEquals(1.0, world.metrics.get(EmailChangeService.METRIC_NAME).tag("operation", "start").tag("result", "accepted").counter().count());
    }

    @Test
    void theDecisionIsRecordedWithoutAddressPhoneOrCode() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        List<RecordingOutbox.Event> records = startRecords();
        assertEquals(1, records.size(), "одна запись аудита на начало смены");
        Map<String, Object> data = records.get(0).data();
        assertEquals(OPERATOR.toString(), data.get("actorId"));
        assertEquals("support-operator", data.get("actorRole"));
        assertEquals("203.0.113.7", data.get("actorIp"));
        assertEquals("ticket", data.get("objectType"));
        assertEquals(ticket.toString(), data.get("objectId"));
        assertEquals(List.of("action", "userId", "source"), ((List<?>) data.get("changes")).stream().map(c -> ((Map<?, ?>) c).get("field")).toList());
        String text = data.get("changes").toString();
        assertFalse(text.contains("@"), "адреса в аудите нет");
        assertFalse(text.contains(BUYER_PHONE), "номера в аудите нет");
        assertFalse(text.contains("111111"), "кода в аудите нет");
    }

    @Test
    void theCodeIsStoredOnlyAsAHashBoundToTheTicket() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        String key = "platform:otp:code:email_change_phone:" + ticket;
        assertTrue(world.otpStore.keys().contains(key), world.otpStore.keys().toString());
        assertFalse(world.otpStore.codes().get(key).hash().contains("111111"), "в хранилище хеш, а не код");
        assertEquals(ticket.toString(), world.otpStore.codes().get(key).confirmationId(), "код привязан к обращению");
    }

    @Test
    void aTakenAddressGivesTheSameNeutralRefusalAsTheBuyersOwnAddressAndSendsNothing() {
        world.accounts.add(UUID.randomUUID(), "taken@example.test", "buyer", T0);

        ProblemException taken = refused(() -> world.emailChange.start(FIRST, ticket, "taken@example.test"));
        ProblemException own = refused(() -> world.emailChange.start(FIRST, ticket, BUYER_EMAIL));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, taken.type());
        assertEquals(taken.type(), own.type());
        assertEquals(taken.getMessage(), own.getMessage(), "ответ на занятый и на свой адрес одинаков, причины в нём нет");
        assertEquals(taken.extensions(), own.extensions());
        assertTrue(world.sms.sent().isEmpty(), "код не отправляется");
        assertTrue(world.mail.sent().isEmpty());
        assertTrue(world.repository.changeOf(ticket).isEmpty(), "этап не записан");
        assertTrue(startRecords().isEmpty(), "записи о начале нет");
        assertNull(world.repository.tickets().get(0).action);
        assertEquals(2.0, world.metrics.get(EmailChangeService.METRIC_NAME).tag("operation", "start").tag("result", "email-not-allowed").counter().count());
    }

    @Test
    void aBuyerWithoutAConfirmedPhoneOrWithoutAnActiveAccountIsAStateConflict() {
        UUID noPhone = UUID.randomUUID();
        world.accounts.add(noPhone, "nophone@example.test", "buyer", T0);
        world.contacts.put(noPhone, "nophone@example.test", "Без телефона");
        UUID noPhoneTicket = world.tickets.create(noPhone, world.issuedOrder(noPhone, T0.minus(Duration.ofHours(1)))).id();
        world.queue.take(FIRST, noPhoneTicket);

        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.start(FIRST, noPhoneTicket, NEW_EMAIL)).type());

        world.contacts.deactivate(BUYER);
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.start(FIRST, ticket, NEW_EMAIL)).type());

        assertTrue(world.sms.sent().isEmpty());
        assertTrue(startRecords().isEmpty());
    }

    @Test
    void onlyTheOperatorWhoTookTheTicketCanStartAndAnUnknownTicketIsNotFound() {
        assertEquals(ProblemType.TICKET_NOT_IN_PROGRESS, refused(() -> world.emailChange.start(SECOND, ticket, NEW_EMAIL)).type());

        world.queue.release(FIRST, ticket);
        assertEquals(ProblemType.TICKET_NOT_IN_PROGRESS, refused(() -> world.emailChange.start(FIRST, ticket, NEW_EMAIL)).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> world.emailChange.start(FIRST, UUID.randomUUID(), NEW_EMAIL)).type());

        assertTrue(world.sms.sent().isEmpty());
        assertTrue(world.repository.changeOf(ticket).isEmpty());
        assertTrue(startRecords().isEmpty());
    }

    @Test
    void startingAgainReplacesTheEarlierAttemptAndItsCode() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        EmailChangeView second = world.emailChange.start(FIRST, ticket, "other.address@mail.example");

        assertEquals("o***@mail.example", second.newEmailMasked());
        assertEquals(2, world.sms.sent().size());
        ProblemException old = refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111"));
        assertEquals(ProblemType.OTP_INVALID, old.type(), "код первой попытки погашен заменой");
        EmailChangeView passed = world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "222222");
        assertEquals("awaiting_email_code", passed.status());
        assertEquals("other.address@mail.example", world.mail.last().orElseThrow().to(), "письмо уходит на адрес последней попытки");
        assertEquals(2, startRecords().size(), "каждая попытка это решение оператора");
    }

    // ---- этапы и коды ----

    @Test
    void bothCodesPassAndTheChangeWaitsForTheCompletionWithoutAskingForCodesAgain() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        EmailChangeView afterPhone = world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        assertEquals("awaiting_email_code", afterPhone.status());
        assertEquals("passed", afterPhone.phoneCheck());
        assertEquals("pending", afterPhone.newEmailCheck());
        assertEquals(1, world.mail.sent().size());
        assertEquals(NEW_EMAIL, world.mail.sent().get(0).to());
        assertEquals("222222", world.mail.sent().get(0).code(), "письмо с кодом ушло напрямую провайдеру");

        world.keycloak.down(true);
        EmailChangeView afterEmail = world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222");
        assertEquals("awaiting_email_code", afterEmail.status(), "Keycloak не отвечает: код принят, а завершение отложено, ответ успешный");
        assertEquals("passed", afterEmail.phoneCheck());
        assertEquals("passed", afterEmail.newEmailCheck());
        assertTrue(change().confirmedNotCompleted(), "точка продолжения: адрес подтверждён, смена не завершена");
        assertEquals(BUYER_EMAIL, world.accounts.find(BUYER).orElseThrow().email(), "адрес учётной записи ещё прежний");
        assertEquals(1, world.sms.sent().size(), "новые коды не запрашивались");
        assertEquals(1, world.mail.sent().size());
    }

    @Test
    void aConfirmedChangeIsNotReplacedByARestartAndNeedsNoNewCodes() {
        confirmWhileKeycloakIsDown();

        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.start(FIRST, ticket, "another@mail.example")).type());
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL)).type());
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222")).type());

        assertTrue(change().confirmedNotCompleted());
        assertEquals(Optional.of(NEW_EMAIL), change().newEmail());
        assertEquals(1, world.sms.sent().size());
        assertEquals(1, world.mail.sent().size());
    }

    @Test
    void aWrongPhoneCodeKeepsTheStageAndCountsAttemptsDownToTheLockAtTheFifth() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        for (int left = 4; left >= 1; left--) {
            ProblemException wrong = refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "000000"));
            assertEquals(ProblemType.OTP_INVALID, wrong.type());
            assertEquals(left, wrong.extensions().get("attemptsLeft"));
            assertEquals("awaiting_phone_code", change().status(), "этап не меняется, пока попытки есть");
        }
        ProblemException locked = refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "000000"));

        assertEquals(ProblemType.OTP_LOCKED, locked.type());
        assertTrue(locked.headers().containsKey("Retry-After"));
        EmailChange failed = change();
        assertEquals("failed", failed.status());
        assertEquals("failed", failed.phoneCheck());
        assertEquals("not_started", failed.newEmailCheck());
        assertEquals(Optional.empty(), failed.newEmail(), "адрес обнулён (INV-44)");
        assertEquals("in_progress", world.repository.tickets().get(0).status, "обращение остаётся в работе");
        assertTrue(world.mail.sent().isEmpty(), "письмо не уходило");
        // Верный код после пятой неудачи уже не принимается: код погашен
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111")).type());
    }

    @Test
    void afterTheLockTheOperatorStartsAgainAndTheNewAttemptWorks() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        for (int i = 0; i < 5; i++) {
            refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "000000"));
        }
        assertEquals("failed", change().status());

        EmailChangeView again = world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        assertEquals("awaiting_phone_code", again.status());
        assertEquals("pending", again.phoneCheck());
        assertEquals("passed", world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "222222").phoneCheck());
    }

    @Test
    void aPhoneCodeExpiresAfterFiveMinutesAndANewRequestGivesAWorkingOne() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        world.clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        // Попытка внутри срока: код верен только при вводе; здесь неверный, чтобы не погасить настоящий
        assertEquals(ProblemType.OTP_INVALID, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "000000")).type());

        world.clock.advance(Duration.ofSeconds(2));
        assertEquals(ProblemType.OTP_EXPIRED, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111")).type());
        assertEquals("awaiting_phone_code", change().status(), "просроченный код не закрывает смену: можно запросить новый");

        EmailChangeView again = world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE);
        assertEquals("awaiting_phone_code", again.status());
        assertEquals(2, world.sms.sent().size());
        assertEquals("passed", world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "222222").phoneCheck());
    }

    @Test
    void aMailCodeExpiresAfterFiveMinutesToo() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");

        world.clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertEquals(ProblemType.OTP_EXPIRED, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222")).type());
        assertEquals("pending", change().newEmailCheck());

        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL);
        assertEquals(2, world.mail.sent().size());
        assertEquals("passed", world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "333333").newEmailCheck());
    }

    @Test
    void aNewMailCodeVoidsTheOldOneAndFiveWrongMailCodesRejectTheChange() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL);

        assertEquals(ProblemType.OTP_INVALID, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222")).type(),
                "прежнее письмо с кодом погашено новым запросом");
        for (int i = 0; i < 3; i++) {
            refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "000000"));
        }
        ProblemException locked = refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "000000"));

        assertEquals(ProblemType.OTP_LOCKED, locked.type());
        EmailChange failed = change();
        assertEquals("failed", failed.status());
        assertEquals("passed", failed.phoneCheck());
        assertEquals("failed", failed.newEmailCheck());
        assertEquals(Optional.empty(), failed.newEmail());
        assertEquals("in_progress", world.repository.tickets().get(0).status);
    }

    @Test
    void theCodeOfOneTicketDoesNotFitAnotherTicketOfTheSameBuyer() {
        UUID secondOrder = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(2)));
        UUID secondTicket = world.tickets.create(BUYER, secondOrder).id();
        world.queue.take(FIRST, secondTicket);
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.start(FIRST, secondTicket, "second.address@mail.example");

        ProblemException foreign = refused(() -> world.emailChange.verify(BUYER, NO_IP, secondTicket, Step.PHONE, "111111"));

        assertEquals(ProblemType.OTP_INVALID, foreign.type(), "код первого обращения ко второму не подходит");
        assertEquals("passed", world.emailChange.verify(BUYER, NO_IP, secondTicket, Step.PHONE, "222222").phoneCheck());
        assertEquals("awaiting_phone_code", change().status(), "первое обращение не затронуто");
    }

    // ---- порядок этапов и доступ ----

    @Test
    void stagesMustComeInOrderAndTheBuyerMustOwnTheTicket() {
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111")).type(),
                "смена не начата");
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE)).type());

        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "111111")).type(),
                "письмо раньше телефона");
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL)).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> world.emailChange.verify(STRANGER, NO_IP, ticket, Step.PHONE, "111111")).type(),
                "чужое обращение не отличается от отсутствующего");
        assertEquals(ProblemType.NOT_FOUND, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, UUID.randomUUID(), Step.PHONE)).type());

        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111")).type(),
                "телефон уже подтверждён");
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE)).type());
    }

    @Test
    void aTicketReturnedToTheQueueStopsTheChange() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.queue.release(FIRST, ticket);

        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111")).type());
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE)).type());
    }

    @Test
    void theOperatorSeesTheStateOfHisOwnTicketOnlyAndOnlyResults() {
        assertEquals(ProblemType.NOT_FOUND, refused(() -> world.emailChange.state(FIRST, ticket)).type(), "смена не начата");

        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");

        EmailChangeView view = world.emailChange.state(FIRST, ticket);
        assertEquals("awaiting_email_code", view.status());
        assertEquals("passed", view.phoneCheck());
        assertEquals("pending", view.newEmailCheck());
        assertEquals("n***@mail.example", view.newEmailMasked());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> world.emailChange.state(SECOND, ticket)).type());
        assertEquals(ProblemType.NOT_FOUND, refused(() -> world.emailChange.state(FIRST, UUID.randomUUID())).type());

        StaffTicket card = world.queue.get(ticket);
        assertEquals(Optional.of(view), card.emailChange(), "карточка обращения показывает то же состояние");
        assertEquals(Optional.of(view), world.tickets.get(BUYER, ticket).emailChange(), "и карточка покупателя");
    }

    // ---- уникальность адреса дважды ----

    @Test
    void anAddressTakenBetweenTheStagesRejectsTheChangeAndNoLetterGoesOut() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.accounts.add(UUID.randomUUID(), NEW_EMAIL, "buyer", T0);

        ProblemException refusal = refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111"));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, refusal.type());
        assertTrue(world.mail.sent().isEmpty(), "на чужой адрес код не уходит никогда (INV-34)");
        EmailChange failed = change();
        assertEquals("failed", failed.status());
        assertEquals(Optional.empty(), failed.newEmail());
        assertEquals("in_progress", world.repository.tickets().get(0).status);
    }

    @Test
    void anAddressTakenBeforeAMailCodeRequestRejectsTheChangeToo() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        world.accounts.add(UUID.randomUUID(), NEW_EMAIL, "buyer", T0);

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL)).type());

        assertEquals(1, world.mail.sent().size(), "второго письма нет");
        assertEquals("failed", change().status());
    }

    // ---- лимиты на управляемых часах ----

    @Test
    void theFourthSmsRequestInTenMinutesIsRefusedAndTheWindowOpensAfterTenMinutes() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE);
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE);

        ProblemException limited = refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertTrue(Long.parseLong(limited.headers().get("Retry-After")) > 0);
        assertEquals(3, world.sms.sent().size(), "четвёртое SMS не отправлено");

        world.clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE);
        assertEquals(4, world.sms.sent().size());
    }

    @Test
    void theEleventhSmsRequestInADayIsRefusedAndTheDayWindowOpensAfterADay() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        for (int i = 1; i < 10; i++) {
            world.clock.advance(Duration.ofMinutes(11));
            world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE);
        }
        assertEquals(10, world.sms.sent().size());

        world.clock.advance(Duration.ofMinutes(11));
        ProblemException limited = refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE));
        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertEquals(10, world.sms.sent().size());

        world.clock.advance(Duration.ofHours(24).plusSeconds(1));
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE);
        assertEquals(11, world.sms.sent().size());
    }

    @Test
    void mailCodeRequestsAreLimitedByTheirOwnWindowsAndDoNotUseUpTheSmsOnes() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL);
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL);

        ProblemException limited = refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertEquals(3, world.mail.sent().size());
        assertEquals(1, world.sms.sent().size());
        assertTrue(world.otpStore.keys().stream().noneMatch(k -> k.contains("example") || k.contains("@")), "адреса в ключах Redis нет: только хеш");
    }

    @Test
    void theEleventhRequestFromOneAddressInAnHourIsRefused() {
        for (int i = 0; i < 10; i++) {
            OtherBuyer other = otherBuyer(i);
            world.emailChange.start(FIRST, other.ticket(), "address" + i + "@mail.example");
        }
        OtherBuyer eleventh = otherBuyer(10);

        ProblemException limited = refused(() -> world.emailChange.start(FIRST, eleventh.ticket(), "address10@mail.example"));

        assertEquals(ProblemType.OTP_REQUEST_LIMITED, limited.type());
        assertEquals(10, world.sms.sent().size(), "одиннадцатое SMS не отправлено");

        world.clock.advance(Duration.ofHours(1).plusSeconds(1));
        world.emailChange.start(FIRST, eleventh.ticket(), "address10@mail.example");
        assertEquals(11, world.sms.sent().size());
    }

    // ---- сбои: Redis и провайдеры ----

    @Test
    void whenRedisIsDownStartAnswersUnavailableAndNeitherSmsNorMailGoesOut() {
        world.otpStore.down(true);

        ProblemException down = refused(() -> world.emailChange.start(FIRST, ticket, NEW_EMAIL));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, down.type());
        assertTrue(world.sms.sent().isEmpty());
        assertTrue(world.mail.sent().isEmpty());

        world.otpStore.down(false);
        EmailChangeView again = world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        assertEquals("awaiting_phone_code", again.status());
        assertEquals(1, world.sms.sent().size());
    }

    @Test
    void whenRedisIsDownVerificationAndMailRequestsAnswerUnavailableToo() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.otpStore.down(true);

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111")).type());
        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, refused(() -> world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE)).type());
        assertEquals("awaiting_phone_code", change().status(), "этап не меняется");
        assertTrue(world.mail.sent().isEmpty());
    }

    @Test
    void aSmsProviderFailureLeavesNoCodeAndTheBuyerCanAskAgain() {
        world.sms.failing(true);

        ProblemException failure = refused(() -> world.emailChange.start(FIRST, ticket, NEW_EMAIL));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, failure.type());
        assertTrue(world.otpStore.keys().stream().noneMatch(k -> k.contains("email_change_phone")), "запись кода убрана вместе с отказом");
        assertEquals("awaiting_phone_code", change().status(), "решение записано, этап ждёт кода");

        world.sms.failing(false);
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.PHONE);
        assertEquals(1, world.sms.sent().size());
        assertEquals("passed", world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, world.sms.last().orElseThrow().code()).phoneCheck());
    }

    @Test
    void aMailProviderFailureAfterThePhoneStepLeavesTheStageForARepeatedRequest() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.mail.failing(true);

        ProblemException failure = refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111"));

        assertEquals(ProblemType.DEPENDENCY_UNAVAILABLE, failure.type());
        assertEquals("awaiting_email_code", change().status(), "телефон подтверждён, письмо не ушло");
        assertEquals("pending", change().newEmailCheck());
        assertTrue(world.otpStore.keys().stream().noneMatch(k -> k.contains("email_change_new_email")));

        world.mail.failing(false);
        world.emailChange.requestCode(BUYER, NO_IP, ticket, Step.NEW_EMAIL);
        assertEquals(1, world.mail.sent().size());
        assertEquals("passed", world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, world.mail.last().orElseThrow().code()).newEmailCheck());
    }

    // ---- закрытие обращения системой ----

    @Test
    void aTicketClosedByTheDeliveryDropsAWaitingChangeAndItsAddress() {
        UUID order = world.repository.tickets().get(0).orderId;
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);

        world.events.onDeliveryDelivered(order, world.clock.instant());

        assertEquals("resolved", world.repository.tickets().get(0).status);
        EmailChange dropped = change();
        assertEquals("failed", dropped.status());
        assertEquals("failed", dropped.phoneCheck());
        assertEquals(Optional.empty(), dropped.newEmail(), "адрес не остаётся в таблице (INV-44)");
        assertEquals("n***@mail.example", dropped.newEmailMasked());
    }

    @Test
    void aTicketClosedByTheDeliveryKeepsAConfirmedChangeForTheCompletion() {
        UUID order = world.repository.tickets().get(0).orderId;
        confirmWhileKeycloakIsDown();

        world.events.onDeliveryDelivered(order, world.clock.instant());

        assertTrue(change().confirmedNotCompleted());
        assertNotEquals(Optional.empty(), change().newEmail());
    }
}
