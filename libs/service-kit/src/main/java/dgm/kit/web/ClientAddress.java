package dgm.kit.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Адрес клиента запроса для журнала аудита. Шлюз ставит {@code X-Forwarded-For} по самому соединению и удаляет заголовки, присланные
 * клиентом (ADR-021, пункт 9), поэтому первый адрес из заголовка надёжен для запросов через шлюз; запрос мимо шлюза (вызов сервиса напрямую по
 * mTLS) даёт адрес соединения.
 */
public final class ClientAddress {

    /** Предел длины адреса в схеме события аудита. */
    public static final int MAX_LENGTH = 128;

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",")[0].strip();
        if (ip == null) {
            return "";
        }
        return ip.length() <= MAX_LENGTH ? ip : ip.substring(0, MAX_LENGTH);
    }
}
