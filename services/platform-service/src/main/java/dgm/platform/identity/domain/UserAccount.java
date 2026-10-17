package dgm.platform.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Учётная запись платформы (таблица {@code identity.user_account}) в объёме, нужном модулю. Телефон наружу не отдаётся.
 *
 * @param id             идентификатор, он же {@code sub} Keycloak
 * @param email          адрес в нормализованном виде; у анонимизированной записи служебное значение
 * @param name           отображаемое имя; у анонимизированной записи «Удалён»
 * @param role           роль
 * @param has2fa         второй фактор включён
 * @param status         {@code active} или {@code deactivated}
 * @param phoneConfirmed телефон подтверждён
 * @param phoneSynced    признак телефона передан в Keycloak
 * @param anonymized     персональные данные заменены
 * @param registeredAt   дата регистрации
 * @param deactivatedAt  дата деактивации
 * @param version        версия строки
 * @param sellerId       профиль продавца в каталоге (SellerID); есть только у роли {@code seller}, проставляется по {@code seller.approved} (Р-18)
 */
public record UserAccount(UUID id, String email, String name, String role, boolean has2fa, String status, boolean phoneConfirmed,
                          boolean phoneSynced, boolean anonymized, Instant registeredAt, Optional<Instant> deactivatedAt, int version,
                          Optional<UUID> sellerId) {

    public static final String ACTIVE = "active";
    public static final String DEACTIVATED = "deactivated";

    /** Запись без профиля продавца: так создаются учётные записи и так они выглядят в тестах до одобрения заявки. */
    public UserAccount(UUID id, String email, String name, String role, boolean has2fa, String status, boolean phoneConfirmed,
                       boolean phoneSynced, boolean anonymized, Instant registeredAt, Optional<Instant> deactivatedAt, int version) {
        this(id, email, name, role, has2fa, status, phoneConfirmed, phoneSynced, anonymized, registeredAt, deactivatedAt, version, Optional.empty());
    }

    public boolean active() {
        return ACTIVE.equals(status);
    }

    /** Только идентификатор: адрес и имя в журнал не попадают. */
    @Override
    public String toString() {
        return "UserAccount[" + id + "]";
    }
}
