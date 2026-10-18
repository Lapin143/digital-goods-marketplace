package dgm.kit.tls;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Properties;
import javax.net.ssl.SSLSocketFactory;

/**
 * Фабрика TLS-соединений для драйвера PostgreSQL: предъявляет серверу сертификат сервиса и доверяет только центру сертификации
 * проекта (взаимная проверка, ADR-022). Рабочие роли {@code app_*} входят только с клиентским сертификатом: правило
 * {@code clientcert=verify-ca} в {@code pg_hba.conf}.
 *
 * <p>Нужна потому, что штатная фабрика драйвера читает закрытый ключ только в формате PKCS#8 DER, а ключи платформы лежат в PEM
 * (одни и те же файлы читают Kafka, Redis и HTTP-клиенты). Драйвер создаёт фабрику по имени класса из параметра строки подключения
 * {@code sslfactory} и передаёт ей все параметры строки; пути к файлам берутся из {@code dgmTlsCa}, {@code dgmTlsCert} и
 * {@code dgmTlsKey} (их выставляет {@link TlsMaterial#jdbcUrl}). Проверку имени узла выполняет сам драйвер
 * ({@code sslmode=verify-full}), цепочку сертификата сервера проверяет доверенное хранилище контекста.
 */
public final class PgSslSocketFactory extends SSLSocketFactory {

    /** Параметры строки подключения с путями к файлам. */
    public static final String CA = "dgmTlsCa";
    public static final String CERT = "dgmTlsCert";
    public static final String KEY = "dgmTlsKey";

    private final SSLSocketFactory delegate;

    /** Конструктор, который ищет драйвер: на вход приходят параметры строки подключения. */
    public PgSslSocketFactory(Properties info) {
        this.delegate = new TlsMaterial(path(info, CA), path(info, CERT), path(info, KEY)).mutualContext().getSocketFactory();
    }

    private static Path path(Properties info, String name) {
        String value = info.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("В строке подключения нет параметра " + name);
        }
        return Path.of(value);
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(socket, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate.createSocket(host, port, localAddress, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
