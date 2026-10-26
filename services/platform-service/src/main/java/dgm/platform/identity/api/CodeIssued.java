package dgm.platform.identity.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Выпущенный код смены e-mail: срок и попытки, самого кода здесь нет (он уходит получателю и в хранилище только хешем).
 *
 * @param expiresAt до какого момента код действует
 * @param attempts  попыток ввода
 * @param resendAt  когда можно запросить новый код; пусто, если можно сразу
 */
public record CodeIssued(Instant expiresAt, int attempts, Optional<Instant> resendAt) {
}
