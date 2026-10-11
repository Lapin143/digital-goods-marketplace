package dgm.kit.integration;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Источник подключений с жёстким пределом одновременных соединений, как пул сервиса (4 соединения на роль, лимит роли в PostgreSQL
 * ниже числа потоков теста). Нужен, чтобы тесты шли в тех же условиях, что и сервис: поток ждёт свободное соединение, а не открывает
 * новое. Закрытие соединения возвращает разрешение.
 */
final class BoundedDataSource implements DataSource {

    private final DataSource delegate;
    private final Semaphore permits;

    BoundedDataSource(DataSource delegate, int size) {
        this.delegate = delegate;
        this.permits = new Semaphore(size, true);
    }

    @Override
    public Connection getConnection() throws SQLException {
        try {
            if (!permits.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new SQLTimeoutException("Нет свободного соединения за 30 секунд");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Ожидание соединения прервано", e);
        }
        try {
            return limited(delegate.getConnection());
        } catch (SQLException | RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    private Connection limited(Connection real) {
        AtomicBoolean released = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                try {
                    real.close();
                } finally {
                    if (released.compareAndSet(false, true)) {
                        permits.release();
                    }
                }
                return null;
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("Учётные данные задаёт исходный источник");
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return delegate.isWrapperFor(type);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
