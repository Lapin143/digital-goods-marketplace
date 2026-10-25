package dgm.kit.config;

/** Тексты сообщений {@code config.changed} для тестов слушателя: конверт CloudEvents с данными {@code ConfigChangedData}. */
final class ConfigMessages {

    private ConfigMessages() {
    }

    static String changed(String key, long value, int version) {
        return changed(key, key, "config.changed", 1, "\"value\":" + value + ",\"version\":" + version);
    }

    /** Сообщение с произвольной серединой данных, чтобы проверять нарушения. */
    static String changed(String subject, String key, String type, int schemaVersion, String valueAndVersion) {
        return """
                {"specversion":"1.0","id":"0199c0de-0000-7000-8000-000000000001","source":"platform-service","type":"%s",
                 "time":"2026-10-25T10:00:00.123Z","subject":"%s","datacontenttype":"application/json","schemaversion":%d,
                 "aggregatetype":"Parameter","traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                 "correlationid":"6b3c0a1e-1111-4111-8111-111111111111",
                 "data":{"key":"%s",%s,"unit":"seconds","changedBy":"5d3b1f79-8c2e-4a60-b4d8-6e0a2c4f1b37"}}
                """.formatted(type, subject, schemaVersion, key, valueAndVersion);
    }
}
