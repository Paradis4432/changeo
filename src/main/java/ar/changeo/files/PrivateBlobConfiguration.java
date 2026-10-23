package ar.changeo.files;

import java.net.URI;
import java.nio.file.*;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class PrivateBlobConfiguration {
    @Bean LocalPrivateBlobStore localPrivateBlobStore(Environment environment) {
        if (!Set.of("local", "r2-preparation").contains(environment.getProperty("changeo.files.storage", "local"))) { throw PrivateBlobStore.unavailable(); }
        return new LocalPrivateBlobStore(Path.of(environment.getProperty("changeo.files-root", ".runtime/content-artifacts")));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "changeo.files.storage", havingValue = "r2-preparation")
    R2PrivateBlobStore r2PrivateBlobStore(Environment environment, Clock clock) {
        URI endpoint;
        Path journal;
        try {
            endpoint = URI.create(environment.getRequiredProperty("changeo.files.r2.endpoint"));
            journal = Path.of(environment.getRequiredProperty("changeo.files.r2.journal-root")).toAbsolutePath().normalize();
        } catch (IllegalArgumentException failure) { throw PrivateBlobStore.unavailable(); }
        Path allowed = Path.of("/home/paradis/changeo/.runtime/r2-storage-preparation-20261019");
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() < 1
                || endpoint.getPort() > 65535 || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || !endpoint.getRawPath().isEmpty()
                || !journal.startsWith(allowed) || journal.equals(allowed)
                || !"changeo-r2-preparation".equals(environment.getProperty("changeo.files.r2.bucket"))
                || !"r2-preparation-key".equals(environment.getProperty("changeo.files.r2.access-key"))
                || !"r2-preparation-secret".equals(environment.getProperty("changeo.files.r2.secret-key"))) { throw PrivateBlobStore.unavailable(); }
        Path current = journal.getRoot();
        for (Path component : journal) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) { throw PrivateBlobStore.unavailable(); }
        }
        return new R2PrivateBlobStore(endpoint, "changeo-r2-preparation", "r2-preparation-key", "r2-preparation-secret", new PrivateBlobJournal(journal, clock));
    }
}
