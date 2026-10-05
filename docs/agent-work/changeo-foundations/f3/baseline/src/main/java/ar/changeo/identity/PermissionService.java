package ar.changeo.identity;

import ar.changeo.identity.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PermissionService implements IdentityAccess {
    private final IdentityStore store;
    private final SecurityProof proof;
    private final Clock clock;
    private final TransactionTemplate protectedTransaction;

    PermissionService(IdentityStore store, SecurityProof proof, Clock clock, PlatformTransactionManager transactions) {
        this.store = store; this.proof = proof; this.clock = clock;
        this.protectedTransaction = new TransactionTemplate(transactions);
        this.protectedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionDecision decide(PermissionRequest request) { return evaluate(request); }

    @Override
    public PermissionDecision requireForUpdate(PermissionRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IdentityFailure("Se requiere una transacción del propietario");
        }
        return protectedTransaction.execute(status -> {
            if (request.actorId().isEmpty() || request.subjectId().isEmpty()) {
                throw new IdentityFailure("Una acción protegida requiere cuentas autenticadas");
            }
            store.lockAccounts(List.of(request.actorId().orElseThrow().value(), request.subjectId().orElseThrow().value()));
            PermissionDecision decision = evaluate(request);
            if (!decision.allowed()) { throw new IdentityFailure(decision.reason()); }
            return decision;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public AccountAccessSnapshot snapshot(AccountId id) {
        if (!proof.actor().filter(id.value()::equals).isPresent()) { throw new IdentityFailure("Acceso propio requerido"); }
        Account account = store.account(id.value());
        int age = store.age(id.value());
        return new AccountAccessSnapshot(id, account.contactVerified, age < 0 ? "Pendiente" : age >= 18 ? "Adulto sintético" : "Menor sintético",
                store.restricted(id.value()), account.sensitivePreference, account.adultPreference,
                "SANDBOX_SYNTHETIC", "Producción pendiente");
    }

    PermissionDecision evaluate(PermissionRequest request) {
        Optional<AccountId> actor = request.actorId().map(id -> new AccountId(id.value()));
        Optional<AccountId> subject = request.subjectId().map(id -> new AccountId(id.value()));
        if (actor.isPresent() != subject.isPresent() || !validContext(request)) { return decision(request, false, "Contexto inválido"); }
        if (actor.isEmpty()) {
            return decision(request, Set.of(Capability.BROWSE_GENERAL, Capability.VIEW_SENSITIVE).contains(request.capability()), "Solo contenido público aprobado con advertencia");
        }
        try {
            Account a = store.account(actor.orElseThrow().value());
            Account s = store.account(subject.orElseThrow().value());
            boolean self = a.id.equals(s.id);
            Capability capability = request.capability();
            if (Set.of(Capability.ACCESS_SUPPORT, Capability.ACCESS_FINANCIAL_RIGHTS, Capability.ACCESS_EXISTING_OBLIGATION).contains(capability)) {
                return decision(request, self, self ? "Acceso controlado; propietario verifica derechos históricos" : "Acceso propio requerido");
            }
            if (capability.name().startsWith("ADMIN_")) {
                AdminRole role = switch (capability) {
                    case ADMIN_IDENTITY -> AdminRole.IDENTITY_ADMIN;
                    case ADMIN_RESTRICTIONS -> AdminRole.RESTRICTION_ADMIN;
                    case ADMIN_AUDIT -> AdminRole.AUDIT_READER;
                    default -> throw new IllegalStateException();
                };
                boolean allowed = self && !store.restricted(a.id) && a.roles.contains(role) && proof.mfa(a.id, a.securityEpoch)
                        && (capability == Capability.ADMIN_AUDIT || proof.freshPassword(a.id, a.securityEpoch));
                return decision(request, allowed, "Rol actual, MFA y autenticación reciente requeridos");
            }
            if (capability == Capability.BROWSE_GENERAL) { return decision(request, self, "Solo contenido público aprobado"); }
            if (capability == Capability.VIEW_SENSITIVE) { return decision(request, self && a.sensitivePreference, "Preferencia y advertencia requeridas"); }
            if (store.restricted(a.id) || store.restricted(s.id) || !a.contactVerified || !s.contactVerified) {
                return decision(request, false, "Contacto o participación pendiente/restringida");
            }
            int age = store.age(s.id);
            boolean adult = age >= 18;
            boolean minor = age >= 0 && age < 18;
            Optional<GuardianLink> link = store.currentLink(s.id).filter(l -> l.authorityExpiresAt != null && l.authorityExpiresAt.isAfter(clock.instant()));
            boolean guardianEligible = link.filter(l -> guardianEligible(l.guardianId)).isPresent();
            boolean guardianActor = minor && guardianEligible && link.filter(l -> l.guardianId.equals(a.id)).isPresent();
            boolean adultSelf = self && adult;
            boolean provider = store.evidence(s.id, EvidenceKind.PROVIDER_ELIGIBILITY).isPresent();
            boolean canProvide = provider && (adultSelf || (age >= 16 && guardianActor));
            boolean allowed = switch (capability) {
                case VIEW_ADULT -> adultSelf && a.adultPreference;
                case DRAFT_REQUEST -> adultSelf || (self && minor && guardianEligible && store.evidence(s.id, EvidenceKind.MINOR_DRAFT).isPresent());
                case DRAFT_OFFER -> provider && (adultSelf || (self && age >= 16 && minor && guardianEligible && store.evidence(s.id, EvidenceKind.MINOR_DRAFT).isPresent()));
                case NEW_REQUEST, SEND_MARKETPLACE_MESSAGE -> adultSelf || guardianActor;
                case NEW_PROVIDER_PARTICIPATION -> canProvide;
                case ACCEPT_AGREEMENT, SYNTHETIC_FUNDING_ELIGIBILITY -> (adultSelf || guardianActor)
                        && (adult || request.jobContext().orElseThrow().fulfillmentMode() != JobContext.FulfillmentMode.LOCAL
                        || link.filter(l -> store.consent(l.id, request.jobContext().orElseThrow().jobId())).isPresent());
                case GRANT_IN_PERSON_CONSENT -> guardianActor;
                default -> false;
            };
            return decision(request, allowed, allowed ? "Elegibilidad sintética; requiere validación del propietario" : "Elegibilidad o autoridad pendiente");
        } catch (IdentityFailure failure) {
            return decision(request, false, "Cuenta no disponible");
        }
    }

    private boolean guardianEligible(UUID id) {
        Account guardian = store.account(id);
        return guardian.contactVerified && store.age(id) >= 18 && !store.restricted(id);
    }

    private boolean validContext(PermissionRequest request) {
        return switch (request.capability()) {
            case ACCEPT_AGREEMENT, SYNTHETIC_FUNDING_ELIGIBILITY, ACCESS_EXISTING_OBLIGATION -> request.jobContext().isPresent();
            case GRANT_IN_PERSON_CONSENT -> request.jobContext().filter(j -> j.fulfillmentMode() == JobContext.FulfillmentMode.LOCAL).isPresent();
            case SEND_MARKETPLACE_MESSAGE, ACCESS_FINANCIAL_RIGHTS -> true;
            default -> request.jobContext().isEmpty();
        };
    }

    private PermissionDecision decision(PermissionRequest request, boolean allowed, String reason) {
        Optional<AccountId> actor = request.actorId().map(id -> new AccountId(id.value()));
        Optional<AccountId> payer = allowed && Set.of(Capability.ACCEPT_AGREEMENT, Capability.SYNTHETIC_FUNDING_ELIGIBILITY).contains(request.capability()) ? actor : Optional.empty();
        return new PermissionDecision(allowed, reason, actor, request.subjectId().map(id -> new AccountId(id.value())), payer, "SANDBOX_SYNTHETIC");
    }
}
