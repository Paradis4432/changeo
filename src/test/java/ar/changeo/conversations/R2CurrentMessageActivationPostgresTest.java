package ar.changeo.conversations;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "spring.flyway.schemas=r2_current_message_activation",
        "spring.flyway.default-schema=r2_current_message_activation",
        "spring.jpa.properties.hibernate.default_schema=r2_current_message_activation",
        "spring.datasource.hikari.schema=r2_current_message_activation"})
class R2CurrentMessageActivationPostgresTest extends MessageActivationPostgresTest {
}
