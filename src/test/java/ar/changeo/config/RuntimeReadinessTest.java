package ar.changeo.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RuntimeReadinessTest {
    private final HikariDataSource pool = mock(HikariDataSource.class);
    private final ApplicationAvailability availability = mock(ApplicationAvailability.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet result = mock(ResultSet.class);
    private DatabaseRuntimeReadiness readiness;

    @BeforeEach
    void configure() throws Exception {
        when(pool.getHikariPoolMXBean()).thenReturn(mock(HikariPoolMXBean.class));
        when(pool.getConnectionTimeout()).thenReturn(500L);
        when(pool.getValidationTimeout()).thenReturn(250L);
        when(pool.isAutoCommit()).thenReturn(true);
        when(pool.getDataSourceClassName()).thenReturn(InitializingPostgresDataSource.class.getName());
        when(pool.getSchema()).thenReturn("runtime_readiness_test");
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getBoolean(1)).thenReturn(true);
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        readiness = readiness(pool, availability, "runtime_readiness_test");
    }

    static DatabaseRuntimeReadiness readiness(HikariDataSource pool, ApplicationAvailability availability, String schema) {
        var flyway = Flyway.configure().dataSource(pool).defaultSchema(schema).schemas(schema).load();
        return new DatabaseRuntimeReadiness(pool, availability, flyway, new DefaultResourceLoader());
    }

    @Test
    void shouldRefuseWithoutBorrowingWhenNativeAvailabilityRefuses() throws Exception {
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertThat(readiness.ready()).isFalse();
        verify(pool, never()).getConnection();
    }

    @Test
    void shouldKeepScopedTimeoutThroughAllCleanupAndCheckAvailabilityAfterReturn() throws Exception {
        doAnswer(invocation -> {
            when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
            return null;
        }).when(connection).close();
        assertThat(readiness.ready()).isFalse();
        var order = inOrder(connection, statement, result);
        order.verify(connection).setNetworkTimeout(any(), eq(500));
        order.verify(connection).prepareStatement(anyString());
        order.verify(statement).setQueryTimeout(1);
        order.verify(statement).setString(eq(1), anyString());
        order.verify(statement).setString(eq(2), anyString());
        order.verify(statement).executeQuery();
        order.verify(result).next();
        order.verify(result).getBoolean(1);
        order.verify(result).wasNull();
        order.verify(result).next();
        order.verify(result).close();
        order.verify(statement).close();
        order.verify(connection).close();
        verify(connection, never()).setNetworkTimeout(any(), eq(0));
        verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"acquisition", "setup", "query", "result", "statement", "reset"})
    void shouldRefuseAndReleasePermitAfterEveryResourceFailure(String stage) throws Exception {
        SQLException failure = new SQLException("private diagnostic", "08006");
        switch (stage) {
            case "acquisition" -> when(pool.getConnection()).thenThrow(failure).thenReturn(connection);
            case "setup" -> doThrow(failure).doNothing().when(connection).setNetworkTimeout(any(), anyInt());
            case "query" -> when(statement.executeQuery()).thenThrow(failure).thenReturn(result);
            case "result" -> doThrow(failure).doNothing().when(result).close();
            case "statement" -> doThrow(failure).doNothing().when(statement).close();
            case "reset" -> doThrow(failure).doNothing().when(connection).close();
            default -> throw new IllegalArgumentException(stage);
        }
        assertThat(readiness.ready()).isFalse();
        when(result.next()).thenReturn(true, false);
        assertThat(readiness.ready()).isTrue();
    }

    @Test
    void shouldRejectConcurrentProbesWithoutBorrowingOrQueueing() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(statement.executeQuery()).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(readiness::ready);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(readiness.ready()).isFalse();
                verify(pool, times(1)).getConnection();
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "false", "null", "multiple"})
    void shouldRefuseMalformedOrDifferentSchemaHeadResult(String shape) throws Exception {
        switch (shape) {
            case "missing" -> when(result.next()).thenReturn(false);
            case "false" -> when(result.getBoolean(1)).thenReturn(false);
            case "null" -> when(result.wasNull()).thenReturn(true);
            case "multiple" -> when(result.next()).thenReturn(true, true);
            default -> throw new IllegalArgumentException(shape);
        }
        assertThat(readiness.ready()).isFalse();
        verify(result).close();
        verify(statement).close();
        verify(connection).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:other://127.0.0.1/private", "jdbc:postgresql://127.0.0.1/private?socketTimeout=-1",
            "jdbc:postgresql://127.0.0.1/private?socketTimeout=2147483647", "jdbc:postgresql://127.0.0.1/private?socketTimeout=secret"})
    void shouldRejectInvalidOrdinaryTimeoutBeforeAnyConnection(String url) {
        var factory = new InitializingPostgresDataSource();
        assertThatThrownBy(() -> factory.setUrl(url)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported database initialization configuration");
    }

    @Test
    void shouldRejectUnsupportedFactoryAndPoolCompositionBeforeBorrowing() throws Exception {
        var factory = new InitializingPostgresDataSource();
        factory.setUrl("jdbc:postgresql://127.0.0.1:1/private?socketTimeout=3&sslmode=verify-full&options=-c%20application_name%3Druntime");
        factory.setLoginTimeout(2);
        assertThatThrownBy(factory::getConnection).isInstanceOf(SQLException.class)
                .hasMessage("Unsupported database initialization configuration");
        when(pool.getConnectionTimeout()).thenReturn(30_000L);
        assertThatThrownBy(() -> readiness(pool, availability, "runtime_readiness_test"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unsupported runtime readiness configuration");
        verify(pool, never()).getConnection();
    }
}
