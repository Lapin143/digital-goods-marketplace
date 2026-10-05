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

    /** Лимит группы: запросов в минуту, запас (пик) в запросах и ключ счёта: {@code ip} или {@code user} (без токена тоже IP). */
    public static class Limit {

        private int perMinute = 60;
        private int burst = 120;
        private String key = "ip";

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
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
    }
}
