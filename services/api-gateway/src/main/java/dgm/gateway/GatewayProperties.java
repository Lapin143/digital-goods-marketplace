package dgm.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Настройки шлюза ({@code dgm.gateway.*}): адреса сервисов-получателей, лимиты по группам маршрутов (ADR-021, таблица маршрутов),
 * размеры тела и сроки. Значения по умолчанию лежат в application.yml.
 */
@ConfigurationProperties(prefix = "dgm.gateway")
public class GatewayProperties {

    /** Сервис или система и адрес, на который идут его маршруты: {@code catalog-service: https://catalog-service:8443}. */
    private Map<String, String> upstreams = new LinkedHashMap<>();

    /** Лимиты по группам: группу маршруту назначает gen_routes.py (OpenAPI) или RouteTable (маршруты вне OpenAPI). */
    private Map<String, Limit> limits = new LinkedHashMap<>();

    private DataSize maxBody = DataSize.ofMegabytes(2);
    private DataSize webhookMaxBody = DataSize.ofKilobytes(64);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration responseTimeout = Duration.ofSeconds(15);

    /** Сколько ждать ответ Redis: дольше нельзя, запрос идёт дальше без проверки лимита (отказ открытым). */
    private Duration limiterTimeout = Duration.ofMillis(200);

    /**
     * Сколько секунд шлюз помнит, что API-ключ действителен (ADR-027, п. 3). Столько же длится окно, в которое отозванный ключ ещё
     * работает: шлюз не подписан на события отзыва. На стенде 2 секунды.
     */
    private int apiKeyCacheSeconds = 30;

    /**
     * Сколько секунд шлюз помнит отказ по ключу (неизвестный, отозванный, продавец не одобрен): короткий срок защищает
     * {@code catalog-service} от повторов одного и того же значения. Не больше {@link #apiKeyCacheSeconds}.
     */
    private int apiKeyNegativeCacheSeconds = 5;

    /** Сколько ждать {@code checkApiKey} у {@code catalog-service}: дольше нельзя, запрос с неизвестным ключом получает 503. */
    private Duration apiKeyCheckTimeout = Duration.ofSeconds(2);

    public Map<String, String> getUpstreams() {
        return upstreams;
    }

    public void setUpstreams(Map<String, String> upstreams) {
        this.upstreams = upstreams;
    }

    public Map<String, Limit> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Limit> limits) {
        this.limits = limits;
    }

    public DataSize getMaxBody() {
        return maxBody;
    }

    public void setMaxBody(DataSize maxBody) {
        this.maxBody = maxBody;
    }

    public DataSize getWebhookMaxBody() {
        return webhookMaxBody;
    }

    public void setWebhookMaxBody(DataSize webhookMaxBody) {
        this.webhookMaxBody = webhookMaxBody;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public Duration getLimiterTimeout() {
        return limiterTimeout;
    }

    public void setLimiterTimeout(Duration limiterTimeout) {
        this.limiterTimeout = limiterTimeout;
    }

    public int getApiKeyCacheSeconds() {
        return apiKeyCacheSeconds;
    }

    public void setApiKeyCacheSeconds(int apiKeyCacheSeconds) {
        this.apiKeyCacheSeconds = apiKeyCacheSeconds;
    }

    public int getApiKeyNegativeCacheSeconds() {
        return apiKeyNegativeCacheSeconds;
    }

    public void setApiKeyNegativeCacheSeconds(int apiKeyNegativeCacheSeconds) {
        this.apiKeyNegativeCacheSeconds = apiKeyNegativeCacheSeconds;
    }

    public Duration getApiKeyCheckTimeout() {
        return apiKeyCheckTimeout;
    }

    public void setApiKeyCheckTimeout(Duration apiKeyCheckTimeout) {
        this.apiKeyCheckTimeout = apiKeyCheckTimeout;
    }

    /** Срок памяти об отказе по ключу: не дольше срока памяти об успехе (на стенде оба короткие). */
    public Duration apiKeyNegativeTtl() {
        return Duration.ofSeconds(Math.max(1, Math.min(apiKeyNegativeCacheSeconds, apiKeyCacheSeconds)));
    }

    public Duration apiKeyTtl() {
        return Duration.ofSeconds(Math.max(1, apiKeyCacheSeconds));
    }

    /**
     * Настройки группы лимита. Имя группы с точкой ({@code seller.api}) в настройках пишется с дефисом ({@code seller-api}): точка в ключе
     * словаря Spring читается как вложенность. Находит группу по имени из таблицы маршрутов.
     */
    public Limit limit(String group) {
        Limit found = limits.get(group);
        return found != null ? found : limits.get(configKey(group));
    }

    /** Имя группы в настройках и в ограничителе: точка заменена дефисом. */
    public static String configKey(String group) {
        return group.replace('.', '-');
    }

    /**
     * Лимит группы: запросов за период (в минуту, либо в час, если задан {@code per-hour}), запас (пик) в запросах и ключ счёта:
     * {@code ip}, {@code user} (пользователь из токена, без токена IP), {@code api-key} (идентификатор ключа, без ключа пользователь
     * или IP) или {@code seller} (профиль продавца: claim {@code seller_id} токена или продавец ключа, общий для кабинета и API).
     */
    public static class Limit {

        private int perMinute = 60;
        private int perHour;
        private int burst = 120;
        private String key = "ip";

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }

        public int getPerHour() {
            return perHour;
        }

        public void setPerHour(int perHour) {
            this.perHour = perHour;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public boolean perUser() {
            return "user".equals(key);
        }

        /** Число запросов за {@link #periodSeconds()}: часовой лимит, если он задан, иначе минутный. */
        public int requestsPerPeriod() {
            return perHour > 0 ? perHour : perMinute;
        }

        /** Длина периода лимита в секундах: час или минута. */
        public int periodSeconds() {
            return perHour > 0 ? 3600 : 60;
        }
    }
}
