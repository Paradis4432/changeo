package ar.changeo;

import ar.changeo.config.SandboxSafety;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ChangeoApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ChangeoApplication.class);
        application.addInitializers(context -> SandboxSafety.validate(context.getEnvironment()));
        if (java.util.Arrays.stream(args).anyMatch(arg -> arg.startsWith("--operator-contact="))) {
            application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
            try (var context = application.run(args)) {
                String contact = context.getEnvironment().getRequiredProperty("operator-contact");
                String roles = context.getEnvironment().getRequiredProperty("operator-roles");
                java.nio.file.Path enrollment = java.nio.file.Path.of(context.getEnvironment().getRequiredProperty("operator-enrollment"));
                context.getBean(ar.changeo.identity.OperatorService.class).provision(contact,
                        java.util.Set.of(roles.split(",")), enrollment);
            }
        } else {
            application.run(args);
        }
    }
}
