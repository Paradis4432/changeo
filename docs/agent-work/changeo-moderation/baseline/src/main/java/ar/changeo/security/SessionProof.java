package ar.changeo.security;

import ar.changeo.identity.SecurityProof;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class SessionProof implements SecurityProof {
    private final Clock clock;
    public SessionProof(Clock clock) { this.clock = clock; }

    @Override public Optional<UUID> actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal
                ? Optional.of(principal.id()) : Optional.empty();
    }

    @Override public boolean mfa(UUID account, long epoch) { return valid("mfa", account, epoch); }
    @Override public boolean freshPassword(UUID account, long epoch) { return valid("password", account, epoch); }

    private boolean valid(String prefix, UUID account, long epoch) {
        if (!actor().filter(account::equals).isPresent()) { return false; }
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) { return false; }
        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null) { return false; }
        Object storedEpoch = session.getAttribute(prefix + "Epoch");
        Object expiry = session.getAttribute(prefix + "Until");
        return storedEpoch instanceof Long value && value == epoch && expiry instanceof Instant until && until.isAfter(clock.instant());
    }
}
