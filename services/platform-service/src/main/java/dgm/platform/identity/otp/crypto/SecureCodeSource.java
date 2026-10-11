package dgm.platform.identity.otp.crypto;

import dgm.platform.identity.otp.service.CodeSource;
import java.security.SecureRandom;
import java.util.Locale;

/** Шесть цифр из криптостойкого генератора (ADR-010); ведущие нули сохраняются. */
public final class SecureCodeSource implements CodeSource {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }
}
