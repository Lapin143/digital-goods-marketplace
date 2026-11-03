package dgm.kit.client;

import dgm.kit.crypto.WebhookSignature;
import dgm.kit.secret.SecretValue;
import dgm.kit.time.Timestamps;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Единственный путь исходящих вызовов к системе продавца (ADR-028, правило 2 руководства Ф5, угроза T-46): запрос выдачи и webhook.
 *
 * <p>Что делает каждый вызов:
 * <ul>
 *   <li>проверяет адрес {@link EndpointGuard} <b>перед</b> вызовом, не только при сохранении; отвергнутый адрес не открывает соединения;</li>
 *   <li>подключается к проверенному {@link java.net.InetAddress}, а не к имени: между проверкой и соединением DNS не спрашивается;
 *       имя продавца идёт в SNI и проверяется по сертификату (цепочка по хранилищу доверия JVM);</li>
 *   <li>подписывает тело {@link WebhookSignature} (заголовки {@code X-Webhook-Signature} и {@code X-Webhook-Timestamp}, метка из
 *       внедряемых часов);</li>
 *   <li>не выполняет перенаправления: ответ 3xx возвращается как есть, а заголовок {@code Location} не читается;</li>
 *   <li>ограничивает вызов {@link #TIMEOUT} целиком (соединение, рукопожатие, запрос и чтение ответа) и ответ {@link #MAX_RESPONSE_BYTES}.</li>
 * </ul>
 *
 * <p>Повторы и состояние запроса ведёт вызывающий (диспетчер доставки, отправитель webhook): этот класс делает ровно один вызов. Адрес,
 * тела и значение секрета не попадают в сообщения исключений и в журнал. Вызывать класс из метода с {@code @Transactional} нельзя (правило
 * 9 руководства, проверяется ArchUnit).
 */
public final class SellerCaller {

    /** Срок вызова целиком (ADR-028). */
    public static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Наибольший принимаемый ответ: тело, которое длиннее, обрывает вызов. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    public static final String CONTENT_TYPE = "application/json; charset=utf-8";

    /** Заголовки, которые выставляет сам вызов и не даёт перекрыть. */
    private static final Set<String> RESERVED = Set.of("host", "content-length", "content-type", "connection", "transfer-encoding",
            "x-webhook-signature", "x-webhook-timestamp");

    /** Ответ продавца: код и тело (не больше {@link #MAX_RESPONSE_BYTES}). */
    public record Response(int status, byte[] body) {

        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /** Код 2xx: продавец принял. */
        public boolean success() {
            return status >= 200 && status < 300;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Вызов не состоялся: тайм-аут, обрыв, слишком большой или неразборчивый ответ. Сообщение не содержит адреса и тел. */
    public static final class CallException extends Exception {

        /** Класс сбоя: вызывающий повторяет любой из них (ADR-028: 5xx, тайм-аут и обрыв считаются временными). */
        public enum Kind {
            TIMEOUT, IO, TOO_LARGE, PROTOCOL
        }

        private final Kind kind;

        public CallException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /** Один запрос к проверенной цели. Подставляется в тестах. */
    @FunctionalInterface
    public interface Transport {
        Response exchange(EndpointGuard.Target target, Map<String, String> headers, byte[] body, Duration timeout) throws CallException;
    }

    private final EndpointGuard guard;
    private final Transport transport;
    private final Clock clock;

    public SellerCaller(EndpointGuard guard, Transport transport, Clock clock) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Рабочая сборка: TLS поверх сокета, подключённого к проверенному адресу. */
    public static SellerCaller over(EndpointGuard guard, SSLSocketFactory tls, Clock clock) {
        return new SellerCaller(guard, new SocketTransport(tls, clock), clock);
    }

    /** Проверка адреса при сохранении подключения (U21): тот же код, что и перед вызовом. Бросает {@link EndpointGuard.RejectedException}. */
    public void validate(String url) {
        guard.check(url);
    }

    /**
     * Подписанный {@code POST} с JSON-телом.
     *
     * @param url     адрес системы продавца, проверяется здесь же
     * @param body    тело запроса (JSON, байты подписываются как есть)
     * @param secret  секрет подписи продавца ({@code SellerSecrets})
     * @param headers дополнительные заголовки (например, {@code X-Event-Id}); служебные заголовки перекрыть нельзя
     * @throws EndpointGuard.RejectedException адрес не разрешён, соединение не открывалось
     * @throws CallException                   вызов не состоялся
     */
    public Response post(String url, byte[] body, SecretValue secret, Map<String, String> headers) throws CallException {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(secret, "secret");
        Map<String, String> outgoing = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Заголовок задаётся самим вызовом: " + name);
            }
            requireToken(name);
            requireValue(header.getValue());
            outgoing.put(name, header.getValue());
        }
        EndpointGuard.Target target = guard.check(url);
        String timestamp = Timestamps.format(clock.instant());
        outgoing.put("Content-Type", CONTENT_TYPE);
        outgoing.put(WebhookSignature.TIMESTAMP_HEADER, timestamp);
        byte[] key = secret.revealBytes();
        try {
            outgoing.put(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(key, timestamp, body));
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
        return transport.exchange(target, Map.copyOf(outgoing), body, TIMEOUT);
    }

    private static void requireToken(String name) {
        if (name.isEmpty() || !name.chars().allMatch(c -> c > 0x20 && c < 0x7F && c != ':')) {
            throw new IllegalArgumentException("Недопустимое имя заголовка");
        }
    }

    private static void requireValue(String value) {
        if (value == null || value.chars().anyMatch(c -> c < 0x20 && c != '\t' || c == 0x7F)) {
            throw new IllegalArgumentException("Недопустимое значение заголовка");
        }
    }

    /** HTTP/1.1 поверх {@link SSLSocket}: одно соединение, один запрос, {@code Connection: close}, без перенаправлений. */
    static final class SocketTransport implements Transport {

        private static final int MAX_LINE = 8 * 1024;
        private static final int MAX_HEADERS = 16 * 1024;

        private final SSLSocketFactory tls;
        private final Clock clock;

        SocketTransport(SSLSocketFactory tls, Clock clock) {
            this.tls = Objects.requireNonNull(tls, "tls");
            this.clock = Objects.requireNonNull(clock, "clock");
        }

        @Override
        public Response exchange(EndpointGuard.Target target, Map<String, String> headers, byte[] body, Duration timeout)
                throws CallException {
            long deadline = clock.millis() + timeout.toMillis();
            Socket plain = new Socket();
            SSLSocket socket = null;
            try {
                plain.connect(new InetSocketAddress(target.address(), target.port()), remaining(deadline));
                plain.setSoTimeout(remaining(deadline));
                // Имя в createSocket идёт в SNI и в проверку сертификата; сам сокет уже подключён к проверенному адресу
                socket = (SSLSocket) tls.createSocket(plain, target.host(), target.port(), true);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.setSoTimeout(remaining(deadline));
                socket.startHandshake();

                OutputStream out = socket.getOutputStream();
                out.write(head(target, headers, body.length));
                out.write(body);
                out.flush();
                return read(socket.getInputStream(), socket, deadline);
            } catch (SocketTimeoutException e) {
                throw new CallException(CallException.Kind.TIMEOUT, "Продавец не ответил за отведённое время", e);
            } catch (IOException e) {
                throw new CallException(CallException.Kind.IO, "Соединение с продавцом не удалось: " + e.getClass().getSimpleName(), e);
            } finally {
                close(socket != null ? socket : plain);
            }
        }

        private int remaining(long deadline) throws SocketTimeoutException {
            long left = deadline - clock.millis();
            if (left <= 0) {
                throw new SocketTimeoutException("Срок вызова истёк");
            }
            return (int) Math.min(left, Integer.MAX_VALUE);
        }

        private static byte[] head(EndpointGuard.Target target, Map<String, String> headers, int length) {
            StringBuilder head = new StringBuilder(256);
            head.append("POST ").append(target.target()).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(target.host().contains(":") ? "[" + target.host() + "]" : target.host());
            if (target.port() != 443) {
                head.append(':').append(target.port());
            }
            head.append("\r\n");
            head.append("Connection: close\r\n");
            head.append("Content-Length: ").append(length).append("\r\n");
            headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
            head.append("\r\n");
            return head.toString().getBytes(StandardCharsets.UTF_8);
        }

        private Response read(InputStream raw, Socket socket, long deadline) throws IOException, CallException {
            InputStream in = new BufferedInputStream(raw);
            String statusLine = line(in, socket, deadline);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/1.") || parts[1].length() != 3 || !parts[1].chars().allMatch(Character::isDigit)) {
                throw new CallException(CallException.Kind.PROTOCOL, "Ответ продавца не похож на HTTP", null);
            }
            int status = Integer.parseInt(parts[1]);
            long contentLength = -1;
            boolean chunked = false;
            int headerBytes = 0;
            for (String header = line(in, socket, deadline); !header.isEmpty(); header = line(in, socket, deadline)) {
                headerBytes += header.length();
                if (headerBytes > MAX_HEADERS) {
                    throw new CallException(CallException.Kind.TOO_LARGE, "Заголовки ответа продавца слишком велики", null);
                }
                int colon = header.indexOf(':');
                if (colon <= 0) {
                    throw new CallException(CallException.Kind.PROTOCOL, "Заголовок ответа продавца разобрать нельзя", null);
                }
                String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = header.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try {
                        contentLength = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        throw new CallException(CallException.Kind.PROTOCOL, "Content-Length ответа продавца неверен", e);
                    }
                    if (contentLength < 0) {
                        throw new CallException(CallException.Kind.PROTOCOL, "Content-Length ответа продавца неверен", null);
                    }
                } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
            if (status < 200 || status == 204 || status == 304) {
                return new Response(status, new byte[0]);
            }
            if (chunked) {
                return new Response(status, chunks(in, socket, deadline));
            }
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new CallException(CallException.Kind.TOO_LARGE, "Ответ продавца больше допустимого", null);
            }
            return new Response(status, contentLength >= 0 ? exactly(in, (int) contentLength, socket, deadline) : untilClose(in, socket, deadline));
        }

        private byte[] exactly(InputStream in, int length, Socket socket, long deadline) throws IOException, CallException {
            byte[] out = new byte[length];
            int done = 0;
            while (done < length) {
                socket.setSoTimeout(remaining(deadline));
                int n = in.read(out, done, length - done);
                if (n < 0) {
                    throw new CallException(CallException.Kind.IO, "Продавец оборвал ответ", null);
                }
                done += n;
            }
            return out;
        }

        private byte[] untilClose(InputStream in, Socket socket, long deadline) throws IOException, CallException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                socket.setSoTimeout(remaining(deadline));
                int n = in.read(buffer);
                if (n < 0) {
                    return out.toByteArray();
                }
                if (out.size() + n > MAX_RESPONSE_BYTES) {
                    throw new CallException(CallException.Kind.TOO_LARGE, "Ответ продавца больше допустимого", null);
                }
                out.write(buffer, 0, n);
            }
        }

        private byte[] chunks(InputStream in, Socket socket, long deadline) throws IOException, CallException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = line(in, socket, deadline);
                int semicolon = sizeLine.indexOf(';');
                String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
                int size;
                try {
                    size = Integer.parseInt(hex, 16);
                } catch (NumberFormatException e) {
                    throw new CallException(CallException.Kind.PROTOCOL, "Размер блока ответа продавца неверен", e);
                }
                if (size < 0) {
                    throw new CallException(CallException.Kind.PROTOCOL, "Размер блока ответа продавца неверен", null);
                }
                if (size == 0) {
                    while (!line(in, socket, deadline).isEmpty()) {
                        // хвостовые заголовки не нужны
                    }
                    return out.toByteArray();
                }
                if ((long) out.size() + size > MAX_RESPONSE_BYTES) {
                    throw new CallException(CallException.Kind.TOO_LARGE, "Ответ продавца больше допустимого", null);
                }
                out.write(exactly(in, size, socket, deadline));
                if (!line(in, socket, deadline).isEmpty()) {
                    throw new CallException(CallException.Kind.PROTOCOL, "Блок ответа продавца не закрыт", null);
                }
            }
        }

        private String line(InputStream in, Socket socket, long deadline) throws IOException, CallException {
            StringBuilder text = new StringBuilder();
            while (true) {
                socket.setSoTimeout(remaining(deadline));
                int c = in.read();
                if (c < 0) {
                    throw new CallException(CallException.Kind.IO, "Продавец оборвал ответ", null);
                }
                if (c == '\n') {
                    int end = text.length();
                    return text.substring(0, end > 0 && text.charAt(end - 1) == '\r' ? end - 1 : end);
                }
                text.append((char) c);
                if (text.length() > MAX_LINE) {
                    throw new CallException(CallException.Kind.TOO_LARGE, "Строка ответа продавца слишком длинна", null);
                }
            }
        }

        private static void close(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // закрытие после ответа или сбоя: сообщать нечего
            }
        }
    }
}
