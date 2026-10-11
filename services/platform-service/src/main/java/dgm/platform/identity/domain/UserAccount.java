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
 */
public record UserAccount(UUID id, String email, String name, String role, boolean has2fa, String status, boolean phoneConfirmed,
                          boolean phoneSynced, boolean anonymized, Instant registeredAt, Optional<Instant> deactivatedAt, int version) {

    public static final String ACTIVE = "active";
    public static final String DEACTIVATED = "deactivated";

    public boolean active() {
        return ACTIVE.equals(status);
    }

    /** Только идентификатор: адрес и имя в журнал не попадают. */
    @Override
    public String toString() {
        return "UserAccount[" + id + "]";
    }
}
