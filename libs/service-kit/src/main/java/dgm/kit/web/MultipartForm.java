package dgm.kit.web;

import dgm.kit.problem.ProblemException;
import dgm.kit.problem.ProblemType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Разбор тела {@code multipart/form-data} (RFC 7578) из байтов, уже прочитанных в память.
 *
 * <p>Встроенный разбор Spring здесь не годится: на маршрутах с {@code Idempotency-Key} тело читает фильтр идемпотентности и отдаёт
 * дальше копию в памяти, а контейнер сервлетов второй раз то же тело не отдаёт. Разбор строгий и небольшой: границы только вида
 * {@code CRLF--граница}, у каждой части есть заголовки, число частей и размер заголовков ограничены, любое отклонение формы это
 * 400 {@code bad-request}. Содержимое файла не интерпретируется: тип по заявленному заголовку не определяется, это делает сервис
 * по самим байтам.
 */
public final class MultipartForm {

    /** Часть формы. {@code fileName} пусто у обычных полей. */
    public record Part(String name, String fileName, String declaredContentType, byte[] content) {
    }

    /** Больше частей в форме не бывает: форма загрузки документа состоит из одной. */
    public static final int MAX_PARTS = 8;
    /** Предел заголовков одной части, байт. */
    public static final int MAX_HEADER_BYTES = 4096;
    private static final int MAX_BOUNDARY = 70;
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private MultipartForm() {
    }

    /**
     * Части формы по порядку.
     *
     * @param contentType заголовок {@code Content-Type} запроса
     * @param body        тело запроса целиком
     * @throws ProblemException {@code unsupported-media-type} (415), если это не {@code multipart/form-data};
     *                          {@code bad-request} (400), если граница или части разобрать нельзя
     */
    public static List<Part> parse(String contentType, byte[] body) {
        if (contentType == null || !contentType.trim().toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw new ProblemException(ProblemType.UNSUPPORTED_MEDIA_TYPE, "Нужна форма multipart/form-data.");
        }
        String boundary = parameter(contentType, "boundary");
        if (boundary == null || boundary.isEmpty() || boundary.length() > MAX_BOUNDARY || boundary.indexOf('\r') >= 0 || boundary.indexOf('\n') >= 0) {
            throw malformed("В Content-Type нет границы формы или она неверна.");
        }
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        int pos = indexOf(body, delimiter, 0);
        if (pos < 0 || pos > 0 && !endsWithLineBreak(body, pos)) {
            throw malformed("Тело формы не начинается с границы.");
        }
        List<Part> parts = new ArrayList<>();
        while (true) {
            pos += delimiter.length;
            if (startsWith(body, pos, new byte[] {'-', '-'})) {
                return parts;
            }
            // После границы допустимы только пробелы и перенос строки
            while (pos < body.length && (body[pos] == ' ' || body[pos] == '\t')) {
                pos++;
            }
            if (!startsWith(body, pos, CRLF)) {
                throw malformed("После границы формы нет переноса строки.");
            }
            pos += CRLF.length;
            if (parts.size() >= MAX_PARTS) {
                throw malformed("В форме больше " + MAX_PARTS + " частей.");
            }
            int headerEnd = indexOf(body, HEADER_END, pos - CRLF.length);
            if (headerEnd < 0 || headerEnd - pos > MAX_HEADER_BYTES) {
                throw malformed("У части формы нет заголовков или они слишком длинные.");
            }
            String headers = new String(body, pos, Math.max(0, headerEnd - pos), StandardCharsets.UTF_8);
            int contentStart = headerEnd + HEADER_END.length;
            // Поиск идёт с последнего CRLF заголовков: у пустой части этот перенос строки и есть начало следующей границы
            int next = indexOf(body, nextDelimiter, contentStart - CRLF.length);
            if (next < 0) {
                throw malformed("Часть формы не закрыта границей.");
            }
            byte[] content = new byte[Math.max(0, next - contentStart)];
            System.arraycopy(body, contentStart, content, 0, content.length);
            parts.add(part(headers, content));
            pos = next + CRLF.length;
        }
    }

    private static Part part(String headers, byte[] content) {
        String disposition = null;
        String type = null;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw malformed("Заголовок части формы не разобран.");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-disposition")) {
                disposition = value;
            } else if (name.equals("content-type")) {
                type = value;
            }
        }
        if (disposition == null || !disposition.toLowerCase(Locale.ROOT).startsWith("form-data")) {
            throw malformed("У части формы нет Content-Disposition: form-data.");
        }
        String name = parameter(disposition, "name");
        if (name == null || name.isEmpty()) {
            throw malformed("У части формы нет имени.");
        }
        return new Part(name, parameter(disposition, "filename"), type, content);
    }

    /** Значение параметра заголовка вида {@code ; имя=значение} или {@code ; имя="значение"} (с экранированием обратной косой чертой). */
    static String parameter(String header, String wanted) {
        int i = header.indexOf(';');
        while (i >= 0 && i < header.length()) {
            i++;
            while (i < header.length() && header.charAt(i) == ' ') {
                i++;
            }
            int eq = header.indexOf('=', i);
            int semicolon = header.indexOf(';', i);
            if (eq < 0 || semicolon >= 0 && semicolon < eq) {
                i = semicolon;
                continue;
            }
            String name = header.substring(i, eq).trim().toLowerCase(Locale.ROOT);
            int j = eq + 1;
            StringBuilder value = new StringBuilder();
            if (j < header.length() && header.charAt(j) == '"') {
                j++;
                while (j < header.length() && header.charAt(j) != '"') {
                    if (header.charAt(j) == '\\' && j + 1 < header.length()) {
                        j++;
                    }
                    value.append(header.charAt(j));
                    j++;
                }
                j++;
            } else {
                while (j < header.length() && header.charAt(j) != ';') {
                    value.append(header.charAt(j));
                    j++;
                }
            }
            if (name.equals(wanted)) {
                return value.toString().trim();
            }
            i = header.indexOf(';', j);
        }
        return null;
    }

    private static ProblemException malformed(String detail) {
        return new ProblemException(ProblemType.BAD_REQUEST, detail);
    }

    private static boolean endsWithLineBreak(byte[] body, int pos) {
        return pos >= 2 && body[pos - 2] == '\r' && body[pos - 1] == '\n';
    }

    private static boolean startsWith(byte[] body, int pos, byte[] prefix) {
        if (pos < 0 || pos + prefix.length > body.length) {
            return false;
        }
        for (int k = 0; k < prefix.length; k++) {
            if (body[pos + k] != prefix[k]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] body, byte[] pattern, int from) {
        int start = Math.max(from, 0);
        outer:
        for (int i = start; i <= body.length - pattern.length; i++) {
            for (int k = 0; k < pattern.length; k++) {
                if (body[i + k] != pattern[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
