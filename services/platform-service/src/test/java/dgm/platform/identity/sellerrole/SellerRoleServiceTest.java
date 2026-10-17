package dgm.platform.identity.sellerrole;

import static dgm.platform.identity.sellerrole.SellerRoleTestKit.SELLER;
import static dgm.platform.identity.sellerrole.SellerRoleTestKit.USER;
import static dgm.platform.identity.sellerrole.SellerRoleTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dgm.kit.events.FatalEventException;
import dgm.kit.events.HandleResult;
import dgm.kit.events.RetryableEventException;
import dgm.platform.identity.domain.UserAccount;
import dgm.platform.identity.service.IdentityEvents;
import dgm.platform.identity.service.IdentityReconciler;
import dgm.platform.identity.testing.FakeTransaction;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Роль «Продавец» по решению модератора (шаг 11 Ф4, INV-36, INV-37, Р-18): запись и события в транзакции, Keycloak после неё. */
class SellerRoleServiceTest {

    private SellerRoleTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new SellerRoleTestKit();
    }

    private List<String> eventTypes() {
        return kit.tx.events().stream().map(FakeTransaction.Event::type).toList();
    }

    private UserAccount account(UUID id) {
        return kit.accounts.find(id).orElseThrow();
    }

    // ---------------------------------------------------------------- назначение

    @Test
    void approvedBuyerBecomesSellerWithTheProfileAndOneRoleEventAndOneAuditRecord() {
        kit.user(USER, "buyer");

        HandleResult result = kit.deliver(USER, SELLER);

        assertEquals(HandleResult.done(), result);
        UserAccount seller = account(USER);
        assertEquals("seller", seller.role());
        assertEquals(Optional.of(SELLER), seller.sellerId());
        assertEquals(List.of("user.role-assigned", "audit.recorded"), eventTypes());

        FakeTransaction.Event role = kit.tx.events("user.role-assigned").get(0);
        assertEquals("identity.events", role.topic());
        assertEquals("User", role.aggregateType());
        assertEquals(USER.toString(), role.aggregateId());
        assertEquals(1, role.schemaVersion());
        assertEquals(Map.of("userId", USER.toString(), "role", "seller", "source", "seller_approval"), role.data(),
                "assignedBy при seller_approval отсутствует, лишних полей нет");

        FakeTransaction.Event audit = kit.tx.events("audit.recorded").get(0);
        assertEquals("audit.events", audit.topic());
        assertEquals("AuditRecord", audit.aggregateType());
        Map<String, Object> data = audit.data();
        assertEquals("system", data.get("actorRole"));
        assertEquals("00000000-0000-0000-0000-000000000000", data.get("actorId"));
        assertEquals("", data.get("actorIp"));
        assertEquals(false, data.get("actorIpHashed"));
        assertEquals("user.role_assigned", data.get("action"));
        assertEquals("user", data.get("objectType"));
        assertEquals(USER.toString(), data.get("objectId"));
        assertEquals(List.of(IdentityEvents.change("role", "buyer", "seller"), IdentityEvents.change("sellerId", null, SELLER.toString())),
                data.get("changes"));
    }

    @Test
    void keycloakIsCalledOnlyAfterTheCommitProfileFirstThenRoleWithTheTotpRequirement() {
        kit.user(USER, "buyer");

        kit.deliver(USER, SELLER);

        // Заглушка бросает AssertionError, если её вызвали внутри транзакции базы (правило 2 среза), поэтому достаточно порядка вызовов
        assertEquals(List.of("setSellerId " + USER, "applyRole seller totp=true " + USER), kit.keycloak.calls());
        assertEquals(Set.of("seller"), kit.keycloak.rolesOf(USER));
        assertEquals(Optional.of(SELLER.toString()), kit.keycloak.sellerIdOf(USER));
        assertEquals(List.of(), kit.tx.failedAfterCommit());
    }

    @Test
    void secondFactorIsNotRequiredAgainWhenTheDeviceIsAlreadyConfigured() {
        kit.user(USER, "buyer");
        kit.accounts.updateHas2fa(USER, true);

        kit.deliver(USER, SELLER);

        assertTrue(kit.keycloak.calls().contains("applyRole seller totp=false " + USER), kit.keycloak.calls().toString());
    }

    // ---------------------------------------------------------------- повторы

    @Test
    void repeatedDeliveryChangesNothingWritesNoSecondEventAndRepeatsTheIdempotentKeycloakCalls() {
        kit.user(USER, "buyer");
        kit.deliver(USER, SELLER);
        UserAccount first = account(USER);

        HandleResult second = kit.deliver(USER, SELLER);

        assertEquals(HandleResult.ignored("already-seller"), second);
        assertEquals(first, account(USER));
        assertEquals(List.of("user.role-assigned", "audit.recorded"), eventTypes());
        assertEquals(4, kit.keycloak.calls().size(), "повторный вызов Keycloak чинит прошлый сбой: он идемпотентен");
    }

    // ---------------------------------------------------------------- недоступный Keycloak

    @Test
    void keycloakOutageAtTheMomentOfApprovalKeepsTheDecisionAndTheReconcilerBringsKeycloakUp() {
        kit.user(USER, "buyer");
        kit.keycloak.down(true);

        HandleResult result = kit.deliver(USER, SELLER);

        assertEquals(HandleResult.done(), result, "решение зафиксировано, повторы потребителя и DLQ не нужны");
        assertEquals("seller", account(USER).role());
        assertEquals(List.of("user.role-assigned", "audit.recorded"), eventTypes());
        assertEquals(List.of(), kit.tx.failedAfterCommit(), "ошибка Keycloak поймана в сервисе");
        assertEquals(Set.of("buyer"), kit.keycloak.rolesOf(USER), "в Keycloak ещё прежняя роль");

        kit.keycloak.down(false);
        IdentityReconciler.Summary pass = kit.reconciler.runPass();

        assertEquals(1, pass.rolesFixed());
        assertEquals(Set.of("seller"), kit.keycloak.rolesOf(USER));
        assertEquals(Optional.of(SELLER.toString()), kit.keycloak.sellerIdOf(USER));
        assertTrue(kit.keycloak.calls().contains("applyRole seller totp=true " + USER), "обязательное действие TOTP выставлено вместе с ролью");
        assertEquals(0, kit.reconciler.runPass().rolesFixed(), "второй проход ничего не меняет");
        assertEquals(List.of("user.role-assigned", "audit.recorded"), eventTypes(), "сверка новых событий не пишет");
    }

    @Test
    void keycloakFailureForTheUserAloneDoesNotRollBackTheDecisionEither() {
        kit.user(USER, "buyer");
        kit.keycloak.failFor(USER);

        assertEquals(HandleResult.done(), kit.deliver(USER, SELLER));

        assertEquals("seller", account(USER).role());
        assertEquals(2, kit.tx.events().size());
    }

    // ---------------------------------------------------------------- события, которые роль не меняют

    @Test
    void accountThatIsNotInThePlatformYetIsRetried() {
        assertThrows(RetryableEventException.class, () -> kit.deliver(USER, SELLER));

        assertEquals(List.of(), kit.tx.events());
        assertEquals(List.of(), kit.keycloak.calls());
    }

    @Test
    void deactivatedAndAnonymizedAccountsAreIgnored() {
        kit.user(USER, "buyer");
        kit.accounts.deactivate(USER, SellerRoleTestKit.NOW);
        assertEquals(HandleResult.ignored("account-inactive"), kit.deliver(USER, SELLER));
        kit.accounts.anonymize(USER);
        assertEquals(HandleResult.ignored("account-inactive"), kit.deliver(USER, SELLER));

        assertEquals("buyer", account(USER).role());
        assertEquals(List.of(), kit.tx.events());
        assertEquals(List.of(), kit.keycloak.calls());
    }

    @Test
    void staffAccountIsNeverDemotedByASellerApplication() {
        for (String role : List.of("moderator", "support-operator", "admin")) {
            UUID staff = id(50 + role.length());
            kit.user(staff, role);

            assertEquals(HandleResult.ignored("role-not-buyer"), kit.deliver(staff, SELLER), role);

            assertEquals(role, account(staff).role());
            assertEquals(Optional.empty(), account(staff).sellerId());
        }
        assertEquals(List.of(), kit.tx.events());
    }

    @Test
    void sellerWithAnotherProfileGoesToTheDeadLetterQueue() {
        kit.user(USER, "buyer");
        UUID other = id(102);
        kit.accounts.assignSeller(USER, other);

        assertThrows(FatalEventException.class, () -> kit.deliver(USER, SELLER));

        assertEquals(Optional.of(other), account(USER).sellerId());
        assertEquals(List.of(), kit.tx.events());
    }

    @Test
    void sellerAddedInTheConsoleWithoutAProfileGetsTheProfileAndTheAuditRecordButNoRoleEvent() {
        kit.user(USER, "seller");

        HandleResult result = kit.deliver(USER, SELLER);

        assertEquals(HandleResult.done(), result);
        assertEquals(Optional.of(SELLER), account(USER).sellerId());
        assertEquals(List.of("audit.recorded"), eventTypes(), "роль не менялась");
        assertEquals(List.of(IdentityEvents.change("sellerId", null, SELLER.toString())), kit.tx.events("audit.recorded").get(0).data().get("changes"));
        assertTrue(kit.keycloak.calls().contains("setSellerId " + USER));
    }

    @Test
    void profileThatBelongsToAnotherAccountIsRefusedAndLeavesNoEventBehind() {
        kit.user(USER, "buyer");
        UUID second = id(2);
        kit.user(second, "buyer");
        kit.deliver(USER, SELLER);
        int events = kit.tx.events().size();
        int calls = kit.keycloak.calls().size();

        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> kit.deliver(second, SELLER));

        assertEquals(events, kit.tx.events().size(), "откат: событий второй попытки нет");
        assertEquals(calls, kit.keycloak.calls().size(), "Keycloak по откатившейся транзакции не вызывался");
        assertFalse(account(second).sellerId().isPresent());
    }
}
