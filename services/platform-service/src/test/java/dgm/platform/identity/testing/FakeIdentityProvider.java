package dgm.platform.identity.testing;

import dgm.kit.keycloak.KeycloakException;
import dgm.kit.keycloak.KeycloakUser;
import dgm.platform.identity.service.IdentityProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Keycloak в памяти: пользователи, роли, второй фактор и журнал вызовов в порядке выполнения. */
public final class FakeIdentityProvider implements IdentityProvider {

    private final Map<UUID, KeycloakUser> users = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> roles = new LinkedHashMap<>();
    private final Set<UUID> otp = new HashSet<>();
    private final Set<UUID> federated = new HashSet<>();
    private final Set<UUID> failing = new HashSet<>();
    private final Set<UUID> phoneVerified = new HashSet<>();
    private final Set<String> takenEmails = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private final FakeTransaction transaction;
    private boolean down;

    /** @param transaction транзакция, внутри которой вызывать Keycloak нельзя; {@code null}, если проверка не нужна */
    public FakeIdentityProvider(FakeTransaction transaction) {
        this.transaction = transaction;
    }

    public static KeycloakUser user(UUID id, String email, String firstName, String lastName, boolean enabled) {
        return new KeycloakUser(id.toString(), email, email, firstName, lastName, enabled, true, 0L, Map.of());
    }

    public FakeIdentityProvider add(KeycloakUser user, String role) {
        UUID id = UUID.fromString(user.id());
        users.put(id, user);
        roles.put(id, new HashSet<>(Set.of(role)));
        return this;
    }

    public FakeIdentityProvider withOtp(UUID id) {
        otp.add(id);
        return this;
    }

    public FakeIdentityProvider withFederation(UUID id) {
        federated.add(id);
        return this;
    }

    /** Keycloak недоступен целиком. */
    public void down(boolean value) {
        this.down = value;
    }

    /** Вызовы по этому пользователю падают с ошибкой Keycloak. */
    public void failFor(UUID id) {
        failing.add(id);
    }

    public void clearFailures() {
        failing.clear();
        down = false;
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public Set<String> rolesOf(UUID id) {
        return Set.copyOf(roles.getOrDefault(id, Set.of()));
    }

    public boolean exists(UUID id) {
        return users.containsKey(id);
    }

    public boolean enabled(UUID id) {
        return users.get(id).enabled();
    }

    /** Атрибут {@code seller_id} пользователя в Keycloak; пусто, если не выставлен. */
    public Optional<String> sellerIdOf(UUID id) {
        KeycloakUser u = users.get(id);
        return u == null ? Optional.empty() : Optional.ofNullable(u.attribute("seller_id"));
    }

    /** Адрес в Keycloak уже у другого пользователя: {@code setEmail} с ним отвечает 409. */
    public void takeEmail(String email) {
        takenEmails.add(email);
    }

    /** E-mail пользователя в Keycloak. */
    public String emailOf(UUID id) {
        return users.get(id).email();
    }

    public boolean emailVerified(UUID id) {
        return users.get(id).emailVerified();
    }

    public boolean phoneVerified(UUID id) {
        return phoneVerified.contains(id);
    }

    private void enter(String call, UUID id) {
        if (transaction != null && transaction.active()) {
            throw new AssertionError("Вызов Keycloak внутри транзакции базы: " + call);
        }
        calls.add(call + " " + id);
        if (down || failing.contains(id)) {
            throw new KeycloakException(0, "Keycloak недоступен", true);
        }
    }

    @Override
    public Optional<KeycloakUser> findUser(UUID id) {
        enter("findUser", id);
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public List<KeycloakUser> listUsers(int first, int max) {
        if (down) {
            calls.add("listUsers");
            throw new KeycloakException(0, "Keycloak недоступен", true);
        }
        calls.add("listUsers " + first);
        return users.values().stream().skip(first).limit(max).toList();
    }

    @Override
    public Set<String> platformRoles(UUID id) {
        enter("platformRoles", id);
        return Set.copyOf(roles.getOrDefault(id, Set.of()));
    }

    @Override
    public boolean hasSecondFactor(UUID id) {
        enter("hasSecondFactor", id);
        return otp.contains(id);
    }

    @Override
    public boolean federated(UUID id) {
        enter("federated", id);
        return federated.contains(id);
    }

    @Override
    public void applyRole(UUID id, String role, boolean requireTotp) {
        enter("applyRole " + role + " totp=" + requireTotp, id);
        roles.put(id, new HashSet<>(Set.of(role)));
    }

    @Override
    public void setSellerId(UUID id, UUID sellerId) {
        enter("setSellerId", id);
        KeycloakUser u = users.get(id);
        if (u != null) {
            Map<String, List<String>> attributes = new LinkedHashMap<>(u.attributes());
            attributes.put("seller_id", List.of(sellerId.toString()));
            users.put(id, new KeycloakUser(u.id(), u.username(), u.email(), u.firstName(), u.lastName(), u.enabled(), u.emailVerified(),
                    u.createdTimestamp(), attributes));
        }
    }

    @Override
    public void disable(UUID id) {
        enter("disable", id);
        KeycloakUser u = users.get(id);
        if (u != null) {
            users.put(id, new KeycloakUser(u.id(), u.username(), u.email(), u.firstName(), u.lastName(), false, u.emailVerified(),
                    u.createdTimestamp(), u.attributes()));
        }
    }

    @Override
    public void delete(UUID id) {
        enter("delete", id);
        users.remove(id);
    }

    @Override
    public void setEmail(UUID id, String email) {
        enter("setEmail", id);
        KeycloakUser u = users.get(id);
        if (u == null) {
            throw new KeycloakException(404, "Пользователя нет в Keycloak", false);
        }
        if (takenEmails.contains(email) && !email.equals(u.email())) {
            throw new KeycloakException(409, "Keycloak ответил 409 на PUT /users/{id}", false);
        }
        users.put(id, new KeycloakUser(u.id(), email, email, u.firstName(), u.lastName(), u.enabled(), true, u.createdTimestamp(), u.attributes()));
    }

    @Override
    public void markPhoneVerified(UUID id) {
        enter("markPhoneVerified", id);
        phoneVerified.add(id);
    }
}
