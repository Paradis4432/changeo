package ar.changeo.payments.gateway;

import ar.changeo.payments.PaymentFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

@Component
@DependsOnDatabaseInitialization
@ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "true")
public class SyntheticGateway implements FinancialGateway {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SecretKeySpec key;

    public SyntheticGateway(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactions,
            @Value("${changeo.payments-auth-key:.runtime/payments/auth.key}") String keyPath) {
        this.jdbc = jdbc; this.clock = clock; this.transaction = new TransactionTemplate(transactions);
        this.key = authenticationKey(Path.of(keyPath));
    }

    @Override public GatewayObservation execute(GatewayRequest request) {
        standalone();
        return transaction.execute(status -> {
            var existing = truth(request, true);
            if (existing.isPresent()) { return existing.orElseThrow(); }
            UUID event = UUID.randomUUID(); var now = clock.instant();
            int inserted = jdbc.update("insert into payments_gateway_truth values(?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing",
                    request.stableKey(), request.operationId(), request.rootId(), request.kind(), request.amountMinor(), request.destination(),
                    "ARS", request.fingerprint(), "SUCCESS", request.amountMinor(), event, Timestamp.from(now));
            if (inserted == 0) { return truth(request, true).orElseThrow(); }
            var observation = signed(event, request, GatewayObservation.Outcome.SUCCESS, request.amountMinor(), now);
            history(observation); return observation;
        });
    }

    @Override public GatewayObservation query(GatewayRequest request) {
        standalone();
        return transaction.execute(status -> truth(request, false).orElseGet(() -> {
            var absent = signed(UUID.randomUUID(), request, GatewayObservation.Outcome.ABSENT, 0, clock.instant());
            history(absent); return absent;
        }));
    }

    @Override public boolean authenticate(GatewayObservation observation) {
        standalone();
        try {
            byte[] supplied = Base64.getDecoder().decode(observation.signature());
            return MessageDigest.isEqual(supplied, signature(observation.eventId(), observation.request(), observation.outcome(), observation.observedMinor(), observation.observedAt()));
        } catch (IllegalArgumentException malformed) { return false; }
    }

    public GatewayObservation recordOutcome(GatewayRequest request, GatewayObservation.Outcome outcome, long observedMinor) {
        standalone();
        if (outcome == GatewayObservation.Outcome.ABSENT) { throw new IllegalArgumentException("La ausencia temporal solo se observa mediante consulta"); }
        return transaction.execute(status -> {
            var existing = truth(request, true);
            if (existing.filter(value -> value.outcome() == outcome && value.observedMinor() == observedMinor).isPresent()) { return existing.orElseThrow(); }
            var observation = signed(UUID.randomUUID(), request, outcome, observedMinor, clock.instant());
            if (existing.isEmpty()) {
                int inserted = jdbc.update("insert into payments_gateway_truth values(?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing",
                        request.stableKey(), request.operationId(), request.rootId(), request.kind(), request.amountMinor(), request.destination(),
                        "ARS", request.fingerprint(), outcome.name(), observedMinor, observation.eventId(), Timestamp.from(observation.observedAt()));
                if (inserted == 0) { throw new PaymentFailure("La clave ya tiene otro resultado; consultá su verdad persistida"); }
            } else {
                jdbc.update("update payments_gateway_truth set outcome=?,observed_minor=?,event_id=? where stable_key=?", outcome.name(), observedMinor, observation.eventId(), request.stableKey());
            }
            history(observation); return observation;
        });
    }

    private Optional<GatewayObservation> truth(GatewayRequest request, boolean lock) {
        var truths = jdbc.queryForList("select * from payments_gateway_truth where stable_key=?" + (lock ? " for update" : ""), request.stableKey());
        if (truths.isEmpty()) { return Optional.empty(); }
        var row = truths.getFirst();
        var persisted = new GatewayRequest((UUID) row.get("operation_id"), (UUID) row.get("root_id"), request.stableKey(), (String) row.get("kind"),
                ((Number) row.get("amount_minor")).longValue(), (String) row.get("destination"), Currency.getInstance((String) row.get("currency")), (String) row.get("fingerprint"));
        if (!persisted.equals(request)) { throw new PaymentFailure("Clave de pasarela ligada a otro destino, importe u operación"); }
        var event = (UUID) row.get("event_id");
        var history = jdbc.queryForList("select created_at from payments_gateway_history where event_id=?", event);
        var observedAt = history.isEmpty() ? ((Timestamp) row.get("created_at")).toInstant() : ((Timestamp) history.getFirst().get("created_at")).toInstant();
        return Optional.of(signed(event, request, GatewayObservation.Outcome.valueOf((String) row.get("outcome")),
                ((Number) row.get("observed_minor")).longValue(), observedAt));
    }

    private void history(GatewayObservation observation) {
        var request = observation.request();
        jdbc.update("insert into payments_gateway_history values(?,?,?,?,?,?,?,?,?,?,?,?)", observation.eventId(), request.stableKey(), request.operationId(),
                request.rootId(), request.kind(), request.amountMinor(), request.destination(), "ARS", request.fingerprint(),
                observation.outcome().name(), observation.observedMinor(), Timestamp.from(observation.observedAt()));
    }

    private GatewayObservation signed(UUID event, GatewayRequest request, GatewayObservation.Outcome outcome, long amount, java.time.Instant at) {
        return new GatewayObservation(event, request, outcome, amount, at, Base64.getEncoder().encodeToString(signature(event, request, outcome, amount, at)));
    }

    private byte[] signature(UUID event, GatewayRequest request, GatewayObservation.Outcome outcome, long amount, java.time.Instant at) {
        String payload = event + "|" + request.operationId() + "|" + request.rootId() + "|" + request.stableKey() + "|" + request.kind()
                + "|" + request.amountMinor() + "|" + request.destination() + "|" + request.currency().getCurrencyCode() + "|" + request.fingerprint()
                + "|" + outcome.name() + "|" + amount + "|" + at;
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(key); return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) { throw new PaymentFailure("Autenticación sintética no disponible", unavailable); }
    }

    private SecretKeySpec authenticationKey(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (jdbc.queryForObject("select count(*) from payments_gateway_truth", Long.class) > 0) { throw new PaymentFailure("Se requiere la clave original de la pasarela; no se reemplaza una historia existente"); }
                Path parent = path.toAbsolutePath().normalize().getParent();
                Files.createDirectories(parent);
                if (Files.isSymbolicLink(parent)) { throw new IOException("Directorio privado inválido"); }
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
                byte[] generated = new byte[32]; new SecureRandom().nextBytes(generated);
                try {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    Files.write(path, generated, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException concurrent) {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw concurrent; }
                }
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                    || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")) || Files.size(path) != 32) {
                throw new IOException("Clave privada inválida");
            }
            return new SecretKeySpec(Files.readAllBytes(path), "HmacSHA256");
        } catch (IOException unavailable) { throw new PaymentFailure("Clave privada sintética no disponible", unavailable); }
    }

    private static void standalone() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new PaymentFailure("La pasarela y su autenticación requieren salir de toda transacción financiera"); }
    }
}
