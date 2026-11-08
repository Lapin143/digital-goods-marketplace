package dgm.keycloak.smsotp.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вызовы {@code issueOtpCode}, {@code verifyOtpCode} и {@code consumeEmailQuota} платформы по HTTPS. На каждую попытку уходит новый {@code Idempotency-Key}
 * (решение Р-27): повтор той же попытки при обрыве связи платформа не выполнит дважды, а новый ввод это новая попытка.
 *
 * <p>Номер, адрес, код и ключи в журнал не попадают: пишется только тип события и код ответа платформы.
 */
public final class HttpPlatformOtp implements PlatformOtp {

    private static final System.Logger LOG = System.getLogger(HttpPlatformOtp.class.getName());
    private static final Pattern PHONE = Pattern.compile("^\\+7[0-9]{10}$");
    private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
    /** Та же форма, что у проверки формы регистрации и у схемы Email платформы; кавычки и косая черта в адресе допустимы, поэтому в JSON он экранируется. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,189}\\.[^@\\s.]{2,}$");
    private static final int EMAIL_MAX = 254;
    private static final Pattern VERIFIED = Pattern.compile("\"verified\"\\s*:\\s*true");
    private static final Pattern IP = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");
    private static final Pattern USER_ID = Pattern.compile(
            "\"userId\"\\s*:\\s*\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");
    private static final Pattern PROBLEM_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([a-z0-9-]{1,64})\"");
    private static final long DEFAULT_WAIT_SECONDS = 60;
    private static final long MAX_WAIT_SECONDS = 24 * 3600;

    private final HttpClient client;
    private final URI base;
    private final Supplier<String> keys;
    private final Duration timeout;

    /**
     * @param client  клиент HTTP (для платформы с взаимной проверкой TLS, см. {@link PemTls})
     * @param base    адрес платформы, например {@code https://platform-service:8443}
     * @param keys    источник ключей идемпотентности, по одному на попытку
     * @param timeout предел одного вызова; ответ входа выравнивается платформой до 0,8 секунды, поэтому берётся с запасом
     */
    public HttpPlatformOtp(HttpClient client, URI base, Supplier<String> keys, Duration timeout) {
        this.client = client;
        this.base = base;
        this.keys = keys;
        this.timeout = timeout;
    }

    /** Источник ключей по умолчанию: случайный UUID. */
    public static Supplier<String> randomKeys() {
        return () -> UUID.randomUUID().toString();
    }

    @Override
    public Issued issue(String phone, String clientIp) {
        requirePhone(phone);
        StringBuilder body = new StringBuilder("{\"purpose\":\"sms_login\",\"phone\":\"").append(phone).append('"');
        if (clientIp != null && IP.matcher(clientIp).matches()) {
            body.append(",\"clientIp\":\"").append(clientIp).append('"');
        }
        body.append('}');
        HttpResponse<String> response = post("/internal/v1/otp-codes", body.toString(), "issueOtpCode");
        if (response == null) {
            return new Issued.Unavailable();
        }
        return switch (response.statusCode()) {
            case 202 -> new Issued.Accepted();
            case 429 -> new Issued.Limited(retryAfter(response));
            default -> {
                LOG.log(System.Logger.Level.WARNING, "issueOtpCode: платформа ответила {0}", response.statusCode());
                yield new Issued.Unavailable();
            }
        };
    }

    @Override
    public Verified verify(String phone, String code) {
        requirePhone(phone);
        requireCode(code);
        String body = "{\"purpose\":\"sms_login\",\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}";
        HttpResponse<String> response = post("/internal/v1/otp-verifications", body, "verifyOtpCode");
        if (response == null) {
            return new Verified.Unavailable();
        }
        switch (response.statusCode()) {
            case 200 -> {
                Matcher user = USER_ID.matcher(response.body());
                if (user.find()) {
                    return new Verified.Accepted(user.group(1).toLowerCase(java.util.Locale.ROOT));
                }
                LOG.log(System.Logger.Level.ERROR, "verifyOtpCode: в ответе 200 нет идентификатора пользователя");
                return new Verified.Unavailable();
            }
            case 422 -> {
                Matcher problem = PROBLEM_CODE.matcher(response.body());
                return problem.find() && "otp-expired".equals(problem.group(1)) ? new Verified.Expired() : new Verified.Invalid();
            }
            case 429 -> {
                return new Verified.Locked(retryAfter(response));
            }
            default -> {
                LOG.log(System.Logger.Level.WARNING, "verifyOtpCode: платформа ответила {0}", response.statusCode());
                return new Verified.Unavailable();
            }
        }
    }

    @Override
    public Issued issueEmail(String email) {
        requireEmail(email);
        String body = "{\"purpose\":\"vk_email\",\"email\":" + jsonString(email) + "}";
        HttpResponse<String> response = post("/internal/v1/otp-codes", body, "issueOtpCode");
        if (response == null) {
            return new Issued.Unavailable();
        }
        return switch (response.statusCode()) {
            case 202 -> new Issued.Accepted();
            case 429 -> new Issued.Limited(retryAfter(response));
            default -> {
                LOG.log(System.Logger.Level.WARNING, "issueOtpCode (vk_email): платформа ответила {0}", response.statusCode());
                yield new Issued.Unavailable();
            }
        };
    }

    @Override
    public EmailChecked verifyEmail(String email, String code) {
        requireEmail(email);
        requireCode(code);
        String body = "{\"purpose\":\"vk_email\",\"email\":" + jsonString(email) + ",\"code\":\"" + code + "\"}";
        HttpResponse<String> response = post("/internal/v1/otp-verifications", body, "verifyOtpCode");
        if (response == null) {
            return new EmailChecked.Unavailable();
        }
        switch (response.statusCode()) {
            case 200 -> {
                if (VERIFIED.matcher(response.body()).find()) {
                    return new EmailChecked.Confirmed();
                }
                LOG.log(System.Logger.Level.ERROR, "verifyOtpCode (vk_email): в ответе 200 нет признака verified");
                return new EmailChecked.Unavailable();
            }
            case 422 -> {
                Matcher problem = PROBLEM_CODE.matcher(response.body());
                return problem.find() && "otp-expired".equals(problem.group(1)) ? new EmailChecked.Expired() : new EmailChecked.Invalid();
            }
            case 429 -> {
                return new EmailChecked.Locked(retryAfter(response));
            }
            default -> {
                LOG.log(System.Logger.Level.WARNING, "verifyOtpCode (vk_email): платформа ответила {0}", response.statusCode());
                return new EmailChecked.Unavailable();
            }
        }
    }

    @Override
    public Quota consumeQuota(String purpose, String email) {
        if (!"register".equals(purpose) && !"reset".equals(purpose)) {
            throw new IllegalArgumentException("Назначение счётчика: register или reset");
        }
        requireEmail(email);
        String body = "{\"purpose\":\"" + purpose + "\",\"email\":" + jsonString(email) + "}";
        HttpResponse<String> response = post("/internal/v1/email-quota/consume", body, "consumeEmailQuota");
        if (response == null) {
            return new Quota.Unavailable();
        }
        return switch (response.statusCode()) {
            case 200 -> new Quota.Granted();
            case 429 -> new Quota.Limited(retryAfter(response));
            default -> {
                LOG.log(System.Logger.Level.WARNING, "consumeEmailQuota: платформа ответила {0}", response.statusCode());
                yield new Quota.Unavailable();
            }
        };
    }

    private HttpResponse<String> post(String path, String body, String operation) {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", keys.get())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "{0}: платформа недоступна ({1})", operation, e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static long retryAfter(HttpResponse<String> response) {
        long seconds = response.headers().firstValue("Retry-After").map(HttpPlatformOtp::parseSeconds).orElse(DEFAULT_WAIT_SECONDS);
        return Math.max(1, Math.min(seconds, MAX_WAIT_SECONDS));
    }

    private static long parseSeconds(String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return DEFAULT_WAIT_SECONDS;
        }
    }

    /** Строка JSON: кавычка, косая черта и управляющие символы экранируются, остальное (в том числе не ASCII) идёт как есть в UTF-8. */
    static String jsonString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void requireEmail(String email) {
        if (email == null || email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Адрес e-mail не прошёл проверку формы");
        }
    }

    private static void requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Код должен состоять из шести цифр");
        }
    }

    private static void requirePhone(String phone) {
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("Номер должен быть в виде +7XXXXXXXXXX");
        }
    }
}
