package dgm.kit.security;

import dgm.kit.secret.SecretValue;
import java.util.List;

/** Источник секретов подписи вебхуков по имени секрета из правила маршрута ({@code x-webhook-secret}). */
@FunctionalInterface
public interface WebhookSecrets {

    /** Действующие секреты; пустой список означает, что секрет не настроен и вебхук принять нельзя. */
    List<SecretValue> forName(String secretName);
}
