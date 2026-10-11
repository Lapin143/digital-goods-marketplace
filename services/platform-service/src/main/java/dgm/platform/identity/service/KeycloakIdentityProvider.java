package dgm.platform.identity.service;

import dgm.kit.boot.KitProperties;
import dgm.kit.keycloak.KeycloakAdminClient;
import dgm.kit.keycloak.KeycloakUser;
import dgm.platform.identity.domain.Roles;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link IdentityProvider} поверх клиента Admin REST Keycloak из каркаса.
 *
 * <p>Роли в realm устроены так (infra/keycloak/gen_realm.py, provision_test_users.py): покупатель держит только роль по умолчанию
 * {@code default-roles-<realm>}, в которую входит {@code buyer}; продавец и сотрудники держат одну свою роль, роль по умолчанию у них
 * снята (одна роль на пользователя, roles-permissions.md, принцип 2). Поэтому роль по умолчанию читается как {@code buyer}.
 */
@Component
public final class KeycloakIdentityProvider implements IdentityProvider {

    static final String TOTP_ACTION = "CONFIGURE_TOTP";
    static final String OTP_CREDENTIAL = "otp";
    static final String PHONE_ATTRIBUTE = "phone_verified";

    private final KeycloakAdminClient keycloak;
    private final String defaultRole;

    public KeycloakIdentityProvider(KeycloakAdminClient keycloak, KitProperties properties) {
        this.keycloak = keycloak;
        this.defaultRole = "default-roles-" + properties.keycloak().realm();
    }

    @Override
    public Optional<KeycloakUser> findUser(UUID id) {
        return keycloak.findUser(id.toString());
    }

    @Override
    public List<KeycloakUser> listUsers(int first, int max) {
        return keycloak.listUsers(first, max);
    }

    @Override
    public Set<String> platformRoles(UUID id) {
        Set<String> roles = new LinkedHashSet<>();
        for (String role : keycloak.realmRoles(id.toString())) {
            if (role.equals(defaultRole)) {
                roles.add(Roles.BUYER);
            } else if (Roles.known(role)) {
                roles.add(role);
            }
        }
        return roles;
    }

    @Override
    public boolean hasSecondFactor(UUID id) {
        return keycloak.hasCredential(id.toString(), OTP_CREDENTIAL);
    }

    @Override
    public boolean federated(UUID id) {
        return !keycloak.federatedProviders(id.toString()).isEmpty();
    }

    /**
     * Сначала назначается нужная роль, потом снимаются лишние: пользователь не остаётся без роли ни на мгновение. Покупателю возвращается
     * роль по умолчанию, остальным она снимается.
     */
    @Override
    public void applyRole(UUID id, String role, boolean requireTotp) {
        String user = id.toString();
        List<String> direct = keycloak.realmRoles(user);
        boolean hasDefault = direct.contains(defaultRole);
        Set<String> known = new LinkedHashSet<>();
        for (String name : direct) {
            if (Roles.known(name)) {
                known.add(name);
            }
        }
        if (Roles.BUYER.equals(role)) {
            if (!hasDefault && !known.contains(Roles.BUYER)) {
                keycloak.addRealmRole(user, defaultRole);
            }
        } else {
            if (!known.contains(role)) {
                keycloak.addRealmRole(user, role);
            }
            if (hasDefault) {
                keycloak.removeRealmRole(user, defaultRole);
            }
        }
        for (String other : known) {
            if (!other.equals(role)) {
                keycloak.removeRealmRole(user, other);
            }
        }
        if (requireTotp) {
            keycloak.addRequiredAction(user, TOTP_ACTION);
        }
    }

    @Override
    public void disable(UUID id) {
        keycloak.setEnabled(id.toString(), false);
        keycloak.logout(id.toString());
    }

    @Override
    public void delete(UUID id) {
        keycloak.deleteUser(id.toString());
    }

    @Override
    public void markPhoneVerified(UUID id) {
        keycloak.setAttributes(id.toString(), Map.of(PHONE_ATTRIBUTE, "true"));
    }
}
