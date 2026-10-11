package dgm.platform.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Данные новой учётной записи. Соль аудита репозиторий создаёт сам при вставке: она нигде, кроме базы, не нужна.
 *
 * @param registeredAt момент создания записи в платформе (по часам сервиса)
 */
public record NewAccount(UUID id, String email, String name, String role, boolean has2fa, Instant registeredAt) {

    @Override
    public String toString() {
        return "NewAccount[" + id + "]";
    }
}
