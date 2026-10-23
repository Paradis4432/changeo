package ar.changeo.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Properties;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;

public final class InitializingPostgresDataSource extends PGSimpleDataSource {
    private static final String INVALID_CONFIGURATION = "Unsupported database initialization configuration";
    private int ordinaryNetworkTimeout;
    private boolean configured;
    private volatile Support support = Support.INITIALIZING;

    private enum Support { INITIALIZING, SUPPORTED, FAILED }

    @Override
    public void setUrl(String url) {
        if (configured) { throw new IllegalArgumentException(INVALID_CONFIGURATION); }
        Properties parsed = url == null ? null : Driver.parseURL(url, null);
        if (parsed == null) { throw new IllegalArgumentException(INVALID_CONFIGURATION); }
        try {
            int seconds = PGProperty.SOCKET_TIMEOUT.getInt(parsed);
            if (seconds < 0) { throw new IllegalArgumentException(INVALID_CONFIGURATION); }
            ordinaryNetworkTimeout = Math.multiplyExact(seconds, 1000);
            super.setUrl(url);
        } catch (SQLException | IllegalArgumentException | ArithmeticException failure) {
            throw new IllegalArgumentException(INVALID_CONFIGURATION);
        }
        super.setConnectTimeout(1);
        super.setSocketTimeout(2);
        super.setCancelSignalTimeout(1);
        configured = true;
    }

    @Override
    public synchronized Connection getConnection(String username, String password) throws SQLException {
        if (support == Support.FAILED) { throw initializationFailure(); }
        if (!configured || getConnectTimeout() != 1 || getSocketTimeout() != 2
                || getCancelSignalTimeout() != 1 || getLoginTimeout() != 1) {
            throw new SQLException(INVALID_CONFIGURATION, "08001");
        }
        Connection connection = super.getConnection(username, password);
        try {
            connection.setNetworkTimeout(Runnable::run, ordinaryNetworkTimeout);
            if (support == Support.SUPPORTED) { return connection; }
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, new InitialConnection(connection));
        } catch (SQLException failure) {
            try { connection.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw new SQLException("Database initialization failed", "08001", failure);
        }
    }

    private static SQLException initializationFailure() {
        return new SQLException("Database initialization failed", "08001");
    }

    private final class InitialConnection implements InvocationHandler {
        private final Connection delegate;

        private InitialConnection(Connection delegate) { this.delegate = delegate; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Initializing PostgreSQL connection";
                    default -> throw new IllegalStateException("Unsupported connection operation");
                };
            }
            if (support == Support.FAILED && !name.equals("close") && !name.equals("isClosed")) {
                throw initializationFailure();
            }
            boolean detecting = support == Support.INITIALIZING
                    && (name.equals("getNetworkTimeout") || name.equals("setNetworkTimeout"));
            try {
                Object result = method.invoke(delegate, arguments);
                if (detecting && name.equals("setNetworkTimeout")) { support = Support.SUPPORTED; }
                return result;
            } catch (InvocationTargetException invocation) {
                Throwable failure = invocation.getCause();
                if (detecting && (failure instanceof Exception || failure instanceof AbstractMethodError)) {
                    support = Support.FAILED;
                    try { delegate.close(); }
                    catch (SQLException | RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
                }
                throw failure;
            }
        }
    }
}
