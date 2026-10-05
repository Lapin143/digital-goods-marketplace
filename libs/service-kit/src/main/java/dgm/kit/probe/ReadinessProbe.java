package dgm.kit.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Проверка готовности для Docker внутри контейнера: HTTP на 127.0.0.1, наружу не открыт. Снаружи состояние отдаёт порт 8444 по
 * mTLS ({@code /actuator/health/readiness}), а здесь проверка без клиентского сертификата: в образе нет curl и openssl, а вторая
 * JVM для проверки не помещается в лимит памяти. Ответ {@code GET /ready}: 200, если все проверки прошли, иначе 503 (в теле только слово).
 */
public final class ReadinessProbe implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReadinessProbe.class);

    private final List<BooleanSupplier> checks;
    private final ServerSocket server;
    private final Thread thread;
    private volatile boolean running = true;

    public ReadinessProbe(int port, List<BooleanSupplier> checks) throws IOException {
        this.checks = List.copyOf(checks);
        this.server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
        this.thread = new Thread(this::serve, "readiness-probe");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Готовность: каждая проверка вернула true, исключение считается отказом. */
    public boolean ready() {
        for (BooleanSupplier check : checks) {
            try {
                if (!check.getAsBoolean()) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    private void serve() {
        while (running) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                answer(socket);
            } catch (IOException e) {
                if (running) {
                    LOG.debug("Запрос проверки готовности не обработан: {}", e.getMessage());
                }
            }
        }
    }

    private void answer(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String line = in.readLine();
        // Заголовки читаем до пустой строки: закрытие сокета с непрочитанными данными сбросило бы соединение до ответа
        for (String header = line; header != null && !header.isEmpty(); header = in.readLine()) {
            if (header.length() > 8_192) {
                break;
            }
        }
        boolean good;
        String body;
        if (line != null && line.startsWith("GET /live ")) {
            good = true;
            body = "live";
        } else if (line != null && line.startsWith("GET /ready ")) {
            good = ready();
            body = good ? "ready" : "not-ready";
        } else {
            good = false;
            body = "not-found";
        }
        int status = good ? 200 : "not-found".equals(body) ? 404 : 503;
        String reason = good ? "OK" : status == 404 ? "Not Found" : "Service Unavailable";
        String response = "HTTP/1.0 " + status + " " + reason + "\r\nContent-Type: text/plain\r\nContent-Length: " + body.length()
                + "\r\nConnection: close\r\n\r\n" + body;
        OutputStream out = socket.getOutputStream();
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException e) {
            LOG.debug("Порт проверки готовности закрыт с ошибкой: {}", e.getMessage());
        }
    }
}
