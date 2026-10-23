package ar.changeo.config;

import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@DependsOnDatabaseInitialization
final class DatabaseRuntimeReadiness implements RuntimeReadiness {
    private static final String INVALID_CONFIGURATION = "Unsupported runtime readiness configuration";
    private static final String PROBE = "select current_schema() = ? and (select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1) = ?";
    private final HikariDataSource pool;
    private final ApplicationAvailability availability;
    private final String schema;
    private final String version;
    private final Semaphore permit = new Semaphore(1);

    DatabaseRuntimeReadiness(DataSource dataSource, ApplicationAvailability availability, Flyway flyway, ResourceLoader resources) {
        if (!(dataSource instanceof HikariDataSource hikari) || hikari.getHikariPoolMXBean() == null
                || hikari.getConnectionTimeout() > 500 || hikari.getValidationTimeout() > 250
                || hikari.isAllowPoolSuspension() || !hikari.isAutoCommit()
                || !InitializingPostgresDataSource.class.getName().equals(hikari.getDataSourceClassName())
                || hikari.getConnectionInitSql() != null) {
            throw new IllegalStateException(INVALID_CONFIGURATION);
        }
        var configuration = flyway.getConfiguration();
        String expectedSchema = configuration.getDefaultSchema();
        if (expectedSchema == null || !expectedSchema.equals(hikari.getSchema())
                || !Arrays.equals(configuration.getSchemas(), new String[]{expectedSchema})
                || configuration.getLocations().length != 1
                || !configuration.getLocations()[0].getDescriptor().equals("classpath:db/migration")
                || !configuration.getSqlMigrationPrefix().equals("V")
                || !configuration.getSqlMigrationSeparator().equals("__")
                || !Arrays.equals(configuration.getSqlMigrationSuffixes(), new String[]{".sql"})
                || !configuration.getTable().equals("flyway_schema_history")
                || configuration.isSkipDefaultResolvers() || configuration.getResolvers().length != 0
                || configuration.getJavaMigrations().length != 0 || configuration.isSkipExecutingMigrations()) {
            throw new IllegalStateException(INVALID_CONFIGURATION);
        }
        this.pool = hikari;
        this.availability = availability;
        this.schema = expectedSchema;
        this.version = packagedVersion(resources);
    }

    @Override
    public boolean ready() {
        if (!accepting() || pool.isClosed() || !permit.tryAcquire()) { return false; }
        try {
            if (!accepting()) { return false; }
            boolean matched;
            try (var connection = pool.getConnection()) {
                if (!accepting()) { return false; }
                connection.setNetworkTimeout(Runnable::run, 500);
                try (var statement = connection.prepareStatement(PROBE)) {
                    statement.setQueryTimeout(1);
                    statement.setString(1, schema);
                    statement.setString(2, version);
                    try (var result = statement.executeQuery()) {
                        matched = result.next() && result.getBoolean(1) && !result.wasNull() && !result.next();
                    }
                }
            }
            return matched && accepting();
        } catch (SQLException failure) {
            return false;
        } finally {
            permit.release();
        }
    }

    private boolean accepting() {
        return availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
    }

    private static String packagedVersion(ResourceLoader loader) {
        var versions = new TreeSet<MigrationVersion>();
        try {
            var resources = ResourcePatternUtils.getResourcePatternResolver(loader).getResources("classpath*:db/migration/V*__*.sql");
            for (var resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) { throw new IllegalStateException(INVALID_CONFIGURATION); }
                var version = MigrationVersion.fromVersion(filename.substring(1, filename.indexOf("__")));
                if (!versions.add(version)) { throw new IllegalStateException(INVALID_CONFIGURATION); }
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException(INVALID_CONFIGURATION);
        }
        if (versions.isEmpty()) { throw new IllegalStateException(INVALID_CONFIGURATION); }
        return versions.last().getVersion();
    }
}
