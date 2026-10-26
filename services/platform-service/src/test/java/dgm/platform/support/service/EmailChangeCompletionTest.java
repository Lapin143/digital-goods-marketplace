package dgm.platform.support.service;

import static dgm.platform.support.testing.SupportWorld.BUYER;
import static dgm.platform.support.testing.SupportWorld.BUYER_EMAIL;
import static dgm.platform.support.testing.SupportWorld.OPERATOR;
import static dgm.platform.support.testing.SupportWorld.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import dgm.platform.identity.service.IdentityEvents;
import dgm.platform.identity.testing.FakeTransaction;
import dgm.platform.support.domain.EmailChange;
import dgm.platform.support.domain.EmailChangeView;
import dgm.platform.support.domain.Operator;
import dgm.platform.support.service.EmailChangeService.Step;
import dgm.platform.support.testing.FakeOrders;
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
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Смена e-mail по обращению, часть 2 (SEQ-04, Р-169): верный код из письма завершает смену (Keycloak, учётная запись, событие
 * {@code user.email-changed}, результат проверки у обращения), следом сама запускается повторная отправка ключа на новый адрес; зависшие этапы доводит
 * сверка, коды заново не запрашиваются; занятый адрес и потерянная учётная запись отклоняют смену, сбой автоповтора её не откатывает. Настоящие службы на
 * хранилищах и Keycloak в памяти, время двигают часы теста.
 */
@Tag("TC-710")
class EmailChangeCompletionTest {

    private static final Operator FIRST = new Operator(OPERATOR, "203.0.113.7");
    private static final String NEW_EMAIL = "new.address@mail.example";
    private static final Optional<String> NO_IP = Optional.empty();
    private static final Duration STUCK_AFTER = Duration.ofSeconds(60);

    private SupportWorld world;
    private UUID ticket;
    private UUID order;

    @BeforeEach
    void setUp() {
        world = new SupportWorld();
        order = world.issuedOrder(BUYER, T0.minus(Duration.ofHours(1)));
        ticket = world.tickets.create(BUYER, order).id();
        world.queue.take(FIRST, ticket);
        world.scriptedCodes.then("111111", "222222", "333333", "444444");
    }

    private static ProblemException refused(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ProblemException.class, action);
    }

    private EmailChange change() {
        return world.repository.changeOf(ticket).orElseThrow();
    }

    /** Оба кода покупателя верны, Keycloak не отвечает на последнем шаге: смена остаётся в точке «адрес подтверждён, не завершён». */
    private void confirmedWhileKeycloakIsDown(UUID forTicket, UUID buyer, String newEmail, String phoneCode, String mailCode) {
        world.emailChange.start(FIRST, forTicket, newEmail);
        world.emailChange.verify(buyer, NO_IP, forTicket, Step.PHONE, phoneCode);
        world.keycloak.down(true);
        world.emailChange.verify(buyer, NO_IP, forTicket, Step.NEW_EMAIL, mailCode);
    }

    private void confirmedWhileKeycloakIsDown() {
        confirmedWhileKeycloakIsDown(ticket, BUYER, NEW_EMAIL, "111111", "222222");
    }

    private void both() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222");
    }

    private int finishStuck() {
        return world.emailChange.finishStuck(world.clock.instant().minus(STUCK_AFTER), EmailChangeReconciler.BATCH);
    }

    private double metric(String operation, String result) {
        var counter = world.metrics.find(EmailChangeService.METRIC_NAME).tag("operation", operation).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private List<RecordingOutbox.Event> resendRecords() {
        return world.outbox.events("audit.recorded").stream().filter(e -> ResendService.ACTION.equals(e.data().get("action"))).toList();
    }

    private static Object fieldAfter(Map<String, Object> record, String field) {
        return ((List<?>) record.get("changes")).stream().map(c -> (Map<?, ?>) c).filter(c -> field.equals(c.get("field"))).map(c -> c.get("after"))
                .findFirst().orElse(null);
    }

    private UUID ticketOfAnotherBuyer(UUID buyer, int index) {
        world.addBuyer(buyer, "second" + index + "@example.test", String.format(Locale.ROOT, "+7900000%04d", index));
        UUID anotherOrder = world.issuedOrder(buyer, T0.minus(Duration.ofHours(1)));
        UUID id = world.tickets.create(buyer, anotherOrder).id();
        world.queue.take(FIRST, id);
        return id;
    }

    // ---- завершение ----

    @Test
    void theMailCodeCompletesTheChangeInKeycloakAndTheAccountAndStartsTheResend() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");

        EmailChangeView done = world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222");

        assertEquals("completed", done.status());
        assertEquals("passed", done.phoneCheck());
        assertEquals("passed", done.newEmailCheck());
        assertEquals(NEW_EMAIL, world.keycloak.emailOf(BUYER));
        assertTrue(world.keycloak.emailVerified(BUYER));
        assertEquals(NEW_EMAIL, world.accounts.find(BUYER).orElseThrow().email());
        assertEquals(BUYER_EMAIL, world.accounts.storedPreviousEmail(BUYER).orElseThrow(), "прежний адрес хранится для письма о смене");
        EmailChange stored = change();
        assertEquals("completed", stored.status());
        assertEquals(Optional.empty(), stored.newEmail(), "нового адреса в записи этапа нет (INV-44)");
        assertEquals("n***@mail.example", stored.newEmailMasked(), "остаётся маска");
        assertEquals(Optional.of("passed"), world.repository.codeCheckOf(ticket));
        assertEquals("in_progress", world.repository.tickets().get(0).status, "обращение остаётся в работе до результата выдачи");
        assertEquals(1, world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).size());
        assertEquals(1, world.identityTransaction.events(IdentityEvents.AUDIT_EVENT).size());
        assertEquals(1.0, metric("complete", "accepted"));
        assertEquals(1.0, metric("verify_new_email", "accepted"));
    }

    @Test
    void theResendIsStartedOnTheNewAddressWithTheDecisionOfTheSameOperator() {
        both();

        List<FakeOrders.Call> calls = world.orders.calls();
        assertEquals(1, calls.size(), "повторная отправка запущена сама, один раз");
        assertEquals(order, calls.get(0).orderId());
        assertEquals(ticket, calls.get(0).ticketId());
        assertEquals(NEW_EMAIL, calls.get(0).email(), "ключ уходит на новый адрес");
        assertEquals(1, world.orders.effects());
        assertEquals("resend", world.repository.tickets().get(0).action);
        assertEquals(Optional.of("queued"), world.repository.findOrderView(order).orElseThrow().deliveryStatus(), "в модели чтения выдача «в очереди»");
        List<RecordingOutbox.Event> records = resendRecords();
        assertEquals(1, records.size());
        Map<String, Object> data = records.get(0).data();
        assertEquals(OPERATOR.toString(), data.get("actorId"), "решение от имени оператора, который вёл смену");
        assertEquals("support-operator", data.get("actorRole"));
        assertEquals("", data.get("actorIp"), "адреса запроса у автоматического решения нет");
        assertEquals("email_change", fieldAfter(data, "source"));
        assertFalse(data.toString().contains(NEW_EMAIL), "адреса в записи аудита нет");
        assertEquals(1.0, metric("resend", "accepted"));
    }

    @Test
    void theAccountEventAndTheAuditRecordCarryNoAddresses() {
        both();

        FakeTransaction.Event event = world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).get(0);
        assertEquals(Map.of("userId", BUYER.toString(), "ticketId", ticket.toString(), "operatorId", OPERATOR.toString()), event.data());
        String everything = world.identityTransaction.events().toString() + world.outbox.events("audit.recorded");
        assertFalse(everything.contains(NEW_EMAIL), "нового адреса в событиях и аудите нет");
        assertFalse(everything.contains(BUYER_EMAIL), "прежнего адреса в событиях и аудите нет");
    }

    @Test
    void completingAgainChangesNothingAndStartsNoSecondResend() {
        both();

        EmailChangeView again = world.emailChange.complete(ticket);

        assertEquals("completed", again.status());
        assertEquals(1, world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).size());
        assertEquals(1, world.orders.calls().size());
        assertEquals(1, resendRecords().size());
        assertEquals(0, finishStuck(), "завершённую смену сверка не берёт");
    }

    @Test
    void anUnconfirmedChangeCannotBeCompleted() {
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.complete(ticket)).type(), "смена не начата");
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.complete(ticket)).type(), "ни одной проверки");
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        assertEquals(ProblemType.STATE_CONFLICT, refused(() -> world.emailChange.complete(ticket)).type(), "нет проверки нового адреса");

        assertEquals(BUYER_EMAIL, world.keycloak.emailOf(BUYER));
        assertTrue(world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).isEmpty());
    }

    // ---- недоступность и сверка ----

    @Test
    void keycloakDownAtTheLastCodeKeepsTheChangeConfirmedAndTheAnswerSuccessful() {
        confirmedWhileKeycloakIsDown();

        EmailChange confirmed = change();
        assertTrue(confirmed.confirmedNotCompleted());
        assertEquals(Optional.of(NEW_EMAIL), confirmed.newEmail());
        assertEquals(BUYER_EMAIL, world.accounts.find(BUYER).orElseThrow().email());
        assertTrue(world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).isEmpty());
        assertTrue(world.orders.calls().isEmpty(), "до завершения повторная отправка не запускается");
        assertEquals(Optional.empty(), world.repository.codeCheckOf(ticket));
        assertEquals(1.0, metric("complete", "dependency-unavailable"));
        assertEquals(1.0, metric("verify_new_email", "accepted"));
    }

    @Test
    void theReconcilerFinishesAConfirmedChangeAfterTheWaitingWithoutNewCodes() {
        confirmedWhileKeycloakIsDown();
        world.keycloak.down(false);

        assertEquals(0, finishStuck(), "смена ещё не простояла положенного: запрос покупателя мог завершить её сам");
        world.clock.advance(STUCK_AFTER.plusSeconds(1));
        assertEquals(1, finishStuck());

        assertEquals("completed", change().status());
        assertEquals(NEW_EMAIL, world.keycloak.emailOf(BUYER));
        assertEquals(NEW_EMAIL, world.accounts.find(BUYER).orElseThrow().email());
        assertEquals(1, world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).size());
        assertEquals(1, world.sms.sent().size(), "SMS заново не запрашивалось");
        assertEquals(1, world.mail.sent().size(), "письмо с кодом заново не отправлялось");
        assertEquals(1, world.orders.calls().size());
        assertEquals(NEW_EMAIL, world.orders.calls().get(0).email());
        assertEquals(0, finishStuck());
    }

    @Test
    void aStillUnavailableKeycloakLeavesTheChangeForTheNextPass() {
        confirmedWhileKeycloakIsDown();
        world.clock.advance(STUCK_AFTER.plusSeconds(1));

        assertEquals(0, finishStuck());
        assertTrue(change().confirmedNotCompleted());

        world.keycloak.down(false);
        world.clock.advance(Duration.ofSeconds(30));
        assertEquals(1, finishStuck());
        assertEquals("completed", change().status());
    }

    @Test
    void oneChangeThatCannotBeFinishedDoesNotStopTheRestOfThePass() {
        UUID secondBuyer = UUID.randomUUID();
        UUID second = ticketOfAnotherBuyer(secondBuyer, 7);
        confirmedWhileKeycloakIsDown(ticket, BUYER, NEW_EMAIL, "111111", "222222");
        world.emailChange.start(FIRST, second, "second.new@mail.example");
        world.keycloak.down(false);
        world.emailChange.verify(secondBuyer, NO_IP, second, Step.PHONE, "333333");
        world.keycloak.down(true);
        world.emailChange.verify(secondBuyer, NO_IP, second, Step.NEW_EMAIL, "444444");
        world.keycloak.down(false);
        world.keycloak.failFor(BUYER);
        world.clock.advance(STUCK_AFTER.plusSeconds(1));

        assertEquals(1, finishStuck(), "вторая смена завершена, первая осталась");

        assertTrue(change().confirmedNotCompleted());
        assertEquals("completed", world.repository.changeOf(second).orElseThrow().status());
        assertEquals("second.new@mail.example", world.accounts.find(secondBuyer).orElseThrow().email());

        world.keycloak.clearFailures();
        assertEquals(1, finishStuck());
        assertEquals("completed", change().status());
        assertEquals(1, world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).stream().filter(e -> BUYER.toString().equals(e.aggregateId())).count());
    }

    @Test
    void aCrashBetweenTheAccountAndTheChangeStageIsFinishedWithoutASecondEvent() {
        confirmedWhileKeycloakIsDown();
        world.keycloak.down(false);
        assertTrue(world.emailChanger.apply(BUYER, NEW_EMAIL, ticket, Optional.of(OPERATOR)), "identity успела изменить адрес, поддержка записать этап не успела");
        assertTrue(change().confirmedNotCompleted());
        world.clock.advance(STUCK_AFTER.plusSeconds(1));

        assertEquals(1, finishStuck());

        assertEquals("completed", change().status());
        assertEquals(1, world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).size(), "событие одно");
        assertEquals(1, world.identityTransaction.events(IdentityEvents.AUDIT_EVENT).size(), "запись аудита одна");
        assertEquals(1, world.orders.calls().size());
        assertEquals(NEW_EMAIL, world.orders.calls().get(0).email());
    }

    // ---- отказы ----

    @Test
    void anAddressTakenInKeycloakRejectsTheConfirmedChangeAndTheOperatorMayStartAgain() {
        world.keycloak.takeEmail(NEW_EMAIL);
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");

        ProblemException refusal = refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222"));

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, refusal.type());
        assertFalse(String.valueOf(refusal.getMessage()).contains(NEW_EMAIL), "ответ нейтральный");
        EmailChange failed = change();
        assertEquals("failed", failed.status());
        assertEquals(Optional.empty(), failed.newEmail());
        assertEquals(BUYER_EMAIL, world.keycloak.emailOf(BUYER));
        assertEquals(BUYER_EMAIL, world.accounts.find(BUYER).orElseThrow().email());
        assertTrue(world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).isEmpty());
        assertTrue(world.orders.calls().isEmpty());
        assertEquals("in_progress", world.repository.tickets().get(0).status);
        assertEquals(1.0, metric("complete", "email-not-allowed"));

        EmailChangeView again = world.emailChange.start(FIRST, ticket, "another.address@mail.example");
        assertEquals("awaiting_phone_code", again.status());
    }

    @Test
    void anAddressTakenOnThePlatformBeforeTheCompletionRejectsTheChangeAndKeycloakIsNotTouched() {
        world.emailChange.start(FIRST, ticket, NEW_EMAIL);
        world.emailChange.verify(BUYER, NO_IP, ticket, Step.PHONE, "111111");
        world.accounts.add(UUID.randomUUID(), NEW_EMAIL, "buyer", T0);

        assertEquals(ProblemType.EMAIL_NOT_ALLOWED, refused(() -> world.emailChange.verify(BUYER, NO_IP, ticket, Step.NEW_EMAIL, "222222")).type());

        assertEquals("failed", change().status());
        assertEquals(BUYER_EMAIL, world.keycloak.emailOf(BUYER));
        assertTrue(world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).isEmpty());
        assertEquals(0, finishStuck(), "отклонённую смену сверка не берёт");
    }

    @Test
    void anAccountDeactivatedBeforeTheCompletionRejectsTheChange() {
        confirmedWhileKeycloakIsDown();
        world.keycloak.down(false);
        world.accounts.deactivate(BUYER, world.clock.instant());
        world.clock.advance(STUCK_AFTER.plusSeconds(1));

        assertEquals(0, finishStuck(), "смена не завершена: учётной записи нет");

        assertEquals("failed", change().status(), "подтверждённая смена отклонена, а не повторяется до бесконечности");
        assertEquals(Optional.empty(), change().newEmail());
        assertTrue(world.identityTransaction.events(IdentityEvents.EMAIL_CHANGED).isEmpty());
        assertEquals("in_progress", world.repository.tickets().get(0).status);
        assertEquals(0, finishStuck());
    }

    // ---- автоповтор ----

    @Test
    void aFailedAutoResendDoesNotRollTheChangeBackAndTheOperatorRepeatsItHimself() {
        world.orders.unavailable();

        both();

        assertEquals("completed", change().status(), "адрес уже изменён, откатывать нечего");
        assertEquals(NEW_EMAIL, world.accounts.find(BUYER).orElseThrow().email());
        assertEquals("in_progress", world.repository.tickets().get(0).status, "обращение в работе, оператор повторит отправку");
        assertEquals("resend", world.repository.tickets().get(0).action, "решение записано до вызова и остаётся");
        assertEquals(1, resendRecords().size());
        assertNotNull(world.repository.findOrderView(order).orElseThrow());
        assertFalse(world.repository.findOrderView(order).orElseThrow().deliveryStatus().filter("queued"::equals).isPresent(), "«в очереди» только после принятого вызова");
        assertEquals(1.0, metric("resend", "dependency-unavailable"));

        world.orders.recover();
        assertEquals(ticket, world.accountResend.resend(FIRST, ticket).ticketId(), "ручная отправка проходит с тем же ключом вызова");
        assertEquals(1, world.orders.effects(), "выдача запрошена один раз");
        assertEquals(NEW_EMAIL, world.orders.calls().get(world.orders.calls().size() - 1).email());
    }

    @Test
    void theAutoResendIsSkippedWhenTheTicketIsNoLongerInProgressAndTheAuditActorIsTheSystem() {
        confirmedWhileKeycloakIsDown();
        world.queue.release(FIRST, ticket);
        world.keycloak.down(false);
        world.clock.advance(STUCK_AFTER.plusSeconds(1));

        assertEquals(1, finishStuck());

        assertEquals("completed", change().status());
        assertTrue(world.orders.calls().isEmpty(), "повторять отправку некому");
        assertEquals(1.0, metric("resend", "skipped"));
        assertNull(world.repository.tickets().get(0).operatorId);
        Map<String, Object> audit = world.identityTransaction.events(IdentityEvents.AUDIT_EVENT).get(0).data();
        assertEquals("system", audit.get("actorRole"), "исполнитель записи «система», когда оператора у обращения уже нет");
        assertEquals(Optional.of("passed"), world.repository.codeCheckOf(ticket), "результат проверки записан и у обращения в очереди");
    }

    @Test
    void aManualResendRightAfterTheAutomaticOneIsRefusedWhileTheDeliveryIsQueued() {
        both();

        // Автоповтор поставил выдачу в очередь: ручная отправка получает тот же отказ, что и любая вторая, лишней выдачи нет
        ProblemException second = refused(() -> world.accountResend.resend(FIRST, ticket));
        assertEquals(ProblemType.DELIVERY_IN_PROGRESS, second.type());
        assertEquals(1, world.orders.calls().size());
    }

    // ---- сверка ----

    @Test
    void theReconcilerTakesAChangeExactlyWhenItHasWaitedLongEnough() {
        confirmedWhileKeycloakIsDown();
        world.keycloak.down(false);
        EmailChangeReconciler reconciler = new EmailChangeReconciler(world.emailChange, new StaticListableBeanFactory().getBeanProvider(dgm.kit.outbox.OutboxRelay.class),
                STUCK_AFTER, world.clock);

        assertEquals(0, reconciler.runPass(), "свежая смена не трогается");
        world.clock.advance(STUCK_AFTER);
        assertEquals(1, reconciler.runPass(), "ровно по истечении срока смена берётся");
        assertEquals(0, reconciler.runPass());
        assertEquals("completed", change().status());
    }

    @Test
    void theScheduledEntryNeverThrowsAndFinishesTheStuckChange() {
        confirmedWhileKeycloakIsDown();
        EmailChangeReconciler reconciler = new EmailChangeReconciler(world.emailChange, new StaticListableBeanFactory().getBeanProvider(dgm.kit.outbox.OutboxRelay.class),
                STUCK_AFTER, world.clock);
        world.clock.advance(STUCK_AFTER.plusSeconds(1));

        reconciler.scheduled();
        assertTrue(change().confirmedNotCompleted(), "Keycloak ещё недоступен: проход ошибкой не завершается, смена ждёт");

        world.keycloak.down(false);
        reconciler.scheduled();
        assertEquals("completed", change().status());
    }
}
