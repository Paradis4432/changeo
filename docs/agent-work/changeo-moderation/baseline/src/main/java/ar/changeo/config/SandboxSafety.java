package ar.changeo.config;

import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;

public final class SandboxSafety {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "[::1]");

    private SandboxSafety() {}

    public static void validate(Environment environment) {
        String[] profiles = environment.getActiveProfiles().length == 0
                ? environment.getDefaultProfiles() : environment.getActiveProfiles();
        if (profiles.length != 1 || !Arrays.asList(profiles).contains("sandbox")) {
            throw new IllegalStateException("Only the private sandbox profile is permitted");
        }
        if (!"false".equals(environment.getProperty("changeo.real-money", "false"))) {
            throw new IllegalStateException("Real money is disabled");
        }
        requireLoopback(environment.getProperty("server.address"));
        if (environment.getProperty("management.server.port") != null) {
            requireLoopback(environment.getProperty("management.server.address"));
        }

    }

    private static void requireLoopback(String address) {
        if (!LOOPBACK.contains(address == null ? "" : address)) {
            throw new IllegalStateException("An explicit loopback listener is required");
        }
    }
}
