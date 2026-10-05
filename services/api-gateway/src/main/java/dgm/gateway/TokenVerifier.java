package dgm.gateway;

import dgm.kit.security.AuthenticatedUser;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Проверка токена доступа: подпись по ключам Keycloak, срок, издатель и адресат. Проверку выполняет тот же декодер каркаса, что и в
 * сервисах (алгоритм none и чужие ключи отклоняются им), поэтому шлюз и сервисы не расходятся. Ключи Keycloak загружаются лениво и
 * кэшируются: загрузка блокирующая, поэтому декодирование уходит из потока Netty в отдельный пул.
 */
public final class TokenVerifier {

    /** Итог проверки. */
    public sealed interface Verdict {

        /** Токен действителен. */
        record Accepted(AuthenticatedUser user) implements Verdict {
        }

        /** Токен отклонён: {@code unavailable} значит «ключи Keycloak получить не удалось» (ответ 503), иначе токен недействителен (401). */
        record Rejected(boolean unavailable) implements Verdict {
        }
    }

    private final JwtDecoder decoder;

    public TokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    public Mono<Verdict> verify(String token) {
        return Mono.<Verdict>fromCallable(() -> new Verdict.Accepted(AuthenticatedUser.from(decoder.decode(token))))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(BadJwtException.class, e -> Mono.just(new Verdict.Rejected(false)))
                .onErrorResume(JwtException.class, e -> Mono.just(new Verdict.Rejected(true)));
    }
}
