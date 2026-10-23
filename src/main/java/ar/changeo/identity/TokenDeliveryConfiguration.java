package ar.changeo.identity;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class TokenDeliveryConfiguration {
    @Bean TokenDeliveryCoordinator tokenDeliveryCoordinator(TokenDelivery local, ObjectProvider<DurableTokenDelivery> durable, Environment environment) {
        String mode = environment.getProperty("changeo.token-delivery", "local");
        if (!mode.equals("local") && !mode.equals("resend-preparation")) { throw new IllegalArgumentException("Unknown token delivery mode"); }
        return new TokenDeliveryCoordinator(local, Optional.ofNullable(durable.getIfAvailable()));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "changeo.token-delivery", havingValue = "resend-preparation")
    static class Preparation {
        @Bean TokenDeliverySpool tokenDeliverySpool(Environment environment, ObjectMapper json) {
            return new TokenDeliverySpool(Path.of(environment.getRequiredProperty("changeo.token-delivery-spool")),
                    Path.of(environment.getRequiredProperty("changeo.token-delivery-key")), json);
        }
        @Bean(destroyMethod = "close") ResendTokenClient resendTokenClient(Environment environment, ObjectMapper json) {
            try {
                Path credential = Path.of(environment.getRequiredProperty("changeo.token-delivery-credential")).toAbsolutePath().normalize();
                TokenDeliverySpool.validateDirectory(credential.getParent());
                String value = new String(TokenDeliverySpool.readPrivate(credential, 256), StandardCharsets.US_ASCII);
                return new ResendTokenClient(URI.create(environment.getRequiredProperty("changeo.token-delivery-endpoint")), value, json);
            } catch (IOException failure) { throw new IllegalStateException("Private delivery credential unavailable"); }
        }
        @Bean DurableTokenDelivery durableTokenDelivery(JdbcTemplate jdbc, IdentityStore identity, Clock clock, TokenDeliverySpool spool, Environment environment) {
            return new DurableTokenDelivery(jdbc, identity, clock, spool, environment.getRequiredProperty("changeo.token-delivery-sender"));
        }
        @Bean TokenDeliveryWorker tokenDeliveryWorker(DurableTokenDelivery store, ResendTokenClient client, PlatformTransactionManager transactions) {
            return new TokenDeliveryWorker(store, client, transactions);
        }
        @Bean @ConditionalOnProperty(name = "changeo.token-delivery-worker", havingValue = "true")
        Schedule tokenDeliverySchedule(TokenDeliveryWorker worker) { return new Schedule(worker); }
    }

    static final class Schedule {
        private final TokenDeliveryWorker worker;
        Schedule(TokenDeliveryWorker worker) { this.worker = worker; }
        @Scheduled(fixedDelay = 3000) void tick() { worker.processOne(); }
    }
}
