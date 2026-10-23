package ar.changeo.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import org.postgresql.Driver;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class RuntimeDatabaseConfiguration {
    private static final String INVALID_CONFIGURATION = "Unsupported database runtime configuration";

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(DataSourceProperties properties, Environment environment, ListableBeanFactory beans) {
        if (beans.getBeanNamesForType(JdbcConnectionDetails.class, true, false).length != 0
                || properties.getUrl() == null || Driver.parseURL(properties.getUrl(), null) == null
                || properties.getType() != null && properties.getType() != HikariDataSource.class
                || properties.getDriverClassName() != null && !properties.getDriverClassName().equals(Driver.class.getName())
                || properties.getJndiName() != null || properties.getXa().getDataSourceClassName() != null
                || !properties.getXa().getProperties().isEmpty()) {
            throw new IllegalStateException(INVALID_CONFIGURATION);
        }
        var pool = new HikariDataSource();
        try {
            pool.setUsername(properties.determineUsername());
            pool.setPassword(properties.determinePassword());
            if (properties.getName() != null && !properties.getName().isBlank()) { pool.setPoolName(properties.getName()); }
            Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
            if (!InitializingPostgresDataSource.class.getName().equals(pool.getDataSourceClassName())
                    || pool.getDataSourceProperties().size() != 1
                    || !properties.getUrl().equals(pool.getDataSourceProperties().getProperty("url"))
                    || !Objects.equals(properties.determineUsername(), pool.getUsername())
                    || !Objects.equals(properties.determinePassword(), pool.getPassword())
                    || pool.getDriverClassName() != null || pool.getJdbcUrl() != null
                    || pool.getDataSource() != null || pool.getDataSourceJNDI() != null
                    || pool.getConnectionInitSql() != null || !pool.isAutoCommit() || pool.isAllowPoolSuspension()
                    || pool.getConnectionTimeout() < 250 || pool.getConnectionTimeout() > 500
                    || pool.getValidationTimeout() != 250 || pool.getInitializationFailTimeout() <= 0) {
                throw new IllegalStateException(INVALID_CONFIGURATION);
            }
            pool.validate();
            return pool;
        } catch (RuntimeException failure) {
            pool.close();
            throw new IllegalStateException(INVALID_CONFIGURATION);
        }
    }
}
