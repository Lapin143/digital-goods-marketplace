package dgm.kit.problem;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Реестр типов проблем (RFC 9457). Единственный источник: docs/05-architecture/conventions.md, раздел 9.1.
 * Коды, статусы и названия сверяет tools/docs-checks/check_kit.py: новый тип добавляется сначала в документ.
 */
public enum ProblemType {
    BAD_REQUEST("bad-request", 400, "Некорректный запрос"),
    IDEMPOTENCY_KEY_REQUIRED("idempotency-key-required", 400, "Нужен ключ идемпотентности"),
    UNAUTHENTICATED("unauthenticated", 401, "Требуется вход"),
    SIGNATURE_INVALID("signature-invalid", 401, "Подпись неверна"),
    WEBHOOK_EXPIRED("webhook-expired", 401, "Уведомление устарело"),
    FORBIDDEN("forbidden", 403, "Недостаточно прав"),
    SECOND_FACTOR_REQUIRED("second-factor-required", 403, "Нужна двухфакторная проверка"),
    FULL_LOGIN_REQUIRED("full-login-required", 403, "Нужен полный вход"),
    PHONE_NOT_CONFIRMED("phone-not-confirmed", 403, "Подтвердите номер телефона"),
    NOT_FOUND("not-found", 404, "Не найдено"),
    STATE_CONFLICT("state-conflict", 409, "Действие недоступно в этом статусе"),
    REQUEST_IN_PROGRESS("request-in-progress", 409, "Запрос уже выполняется"),
    PRODUCT_UNAVAILABLE("product-unavailable", 409, "Товар недоступен"),
    INSUFFICIENT_STOCK("insufficient-stock", 409, "Недостаточно ключей"),
    UNPAID_ORDERS_LIMIT("unpaid-orders-limit", 409, "Слишком много неоплаченных заказов"),
    TICKET_WINDOW_CLOSED("ticket-window-closed", 409, "Срок обращения истёк"),
    TICKET_ALREADY_OPEN("ticket-already-open", 409, "Обращение уже открыто"),
    APPLICATION_PAUSE_ACTIVE("application-pause-active", 409, "Повторная заявка пока недоступна"),
    REFUND_NOT_ALLOWED("refund-not-allowed", 409, "Возврат недоступен"),
    EMAIL_NOT_ALLOWED("email-not-allowed", 409, "Адрес использовать нельзя"),
    PHONE_NOT_ALLOWED("phone-not-allowed", 409, "Номер использовать нельзя"),
    POOL_LIMIT_EXCEEDED("pool-limit-exceeded", 409, "Пул ключей переполнен"),
    LAST_ADMIN_REQUIRED("last-admin-required", 409, "Нужен хотя бы один администратор"),
    ROLE_MANAGED_BY_SYSTEM("role-managed-by-system", 409, "Роль управляется системой"),
    VERSION_CONFLICT("version-conflict", 412, "Ресурс изменился"),
    PAYLOAD_TOO_LARGE("payload-too-large", 413, "Слишком большой запрос"),
    UNSUPPORTED_MEDIA_TYPE("unsupported-media-type", 415, "Неподдерживаемый тип"),
    VALIDATION_FAILED("validation-failed", 422, "Данные не прошли проверку"),
    IDEMPOTENCY_KEY_REUSE("idempotency-key-reuse", 422, "Ключ уже использован"),
    OTP_INVALID("otp-invalid", 422, "Код неверен"),
    OTP_EXPIRED("otp-expired", 422, "Код устарел"),
    PRECONDITION_REQUIRED("precondition-required", 428, "Нужен заголовок `If-Match`"),
    RATE_LIMITED("rate-limited", 429, "Слишком много запросов"),
    OTP_REQUEST_LIMITED("otp-request-limited", 429, "Слишком много запросов кода"),
    OTP_LOCKED("otp-locked", 429, "Код заблокирован"),
    INTERNAL_ERROR("internal-error", 500, "Внутренняя ошибка"),
    PAYMENT_SESSION_UNAVAILABLE("payment-session-unavailable", 503, "Не удалось открыть оплату"),
    DEPENDENCY_UNAVAILABLE("dependency-unavailable", 503, "Сервис временно недоступен"),
    GATEWAY_TIMEOUT("gateway-timeout", 504, "Время ожидания истекло");

    /** Основа адреса типа: тип проблемы это адрес-имя, открываться он не обязан. */
    public static final String TYPE_BASE = "https://api.marketplace.example/problems/";

    private static final Map<String, ProblemType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ProblemType::code, Function.identity()));

    private final String code;
    private final int status;
    private final String title;

    ProblemType(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    /** Стабильный код типа: последний сегмент адреса `type`, по нему клиент выбирает поведение. */
    public String code() {
        return code;
    }

    /** Код HTTP. */
    public int status() {
        return status;
    }

    /** Короткое название типа на русском, одно и то же для всех случаев типа. */
    public String title() {
        return title;
    }

    /** Адрес-имя типа для члена `type`. */
    public String typeUri() {
        return TYPE_BASE + code;
    }

    public static Optional<ProblemType> byCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
