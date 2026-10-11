package dgm.platform.identity.otp.testing;

import dgm.platform.auditadmin.api.PlatformParameters;
import java.util.HashMap;
import java.util.Map;

/** Параметры платформы в памяти. Незаданный ключ отдаёт значение по умолчанию вызывающего. */
public final class FakeParameters implements PlatformParameters {

    private final Map<String, Integer> values = new HashMap<>();

    public FakeParameters set(String key, int value) {
        values.put(key, value);
        return this;
    }

    @Override
    public int value(String key, int fallback) {
        return values.getOrDefault(key, fallback);
    }
}
