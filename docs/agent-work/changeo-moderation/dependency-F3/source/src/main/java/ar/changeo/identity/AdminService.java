package ar.changeo.identity;

import ar.changeo.identity.api.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {
    private final IdentityStore store;
    private final PermissionService permissions;
    private final Clock clock;
    private final boolean syntheticEvidence;

    AdminService(IdentityStore store, PermissionService permissions, Clock clock,
                 @Value("${changeo.synthetic-evidence:false}") boolean syntheticEvidence) {
        this.store = store; this.permissions = permissions; this.clock = clock; this.syntheticEvidence = syntheticEvidence;
    }

    @Transactional(readOnly = true)
    public List<AccountView> accounts(UUID actor) {
        if (!allowed(actor, Capability.ADMIN_IDENTITY) && !allowed(actor, Capability.ADMIN_RESTRICTIONS)) {
            throw new IdentityFailure("Rol, MFA y autenticación reciente requeridos");
        }
        return store.queries().createQuery("select a from Account a order by a.id", Account.class).setMaxResults(100)
                .getResultList().stream().map(a -> new AccountView(a.id, a.contactVerified, store.age(a.id), store.restricted(a.id), a.roles.stream().map(Enum::name).sorted().toList())).toList();
    }

    @Transactional(readOnly = true)
    public List<GuardianService.LinkView> links(UUID actor) {
        require(actor, Capability.ADMIN_IDENTITY);
        return store.queries().createQuery("select l from GuardianLink l order by l.id", GuardianLink.class).setMaxResults(100)
                .getResultList().stream().map(GuardianService::view).toList();
    }

    @Transactional
    public void evidence(UUID actor, UUID target, String kind, Integer age) {
        store.lockAccounts(List.of(actor, target)); require(actor, Capability.ADMIN_IDENTITY);
        if (actor.equals(target) || !syntheticEvidence) { throw new IdentityFailure("La autoelevación está bloqueada; adaptador sintético deshabilitado"); }
        EvidenceKind type;
        try { type = EvidenceKind.valueOf(kind); }
        catch (IllegalArgumentException failure) { throw new IdentityFailure("Tipo de evidencia inválido"); }
        if (type == EvidenceKind.AGE && (age == null || age < 0 || age > 120)) { throw new IdentityFailure("Edad sintética inválida"); }
        Evidence evidence = new Evidence(); evidence.id = UUID.randomUUID(); evidence.accountId = target;
        evidence.kind = type; evidence.age = type == EvidenceKind.AGE ? age : null;
        evidence.createdAt = clock.instant(); evidence.expiresAt = clock.instant().plusSeconds(86400);
        evidence.provenance = "LOCAL_SYNTHETIC_OPERATOR:" + actor; evidence.scope = "SANDBOX_SYNTHETIC";
        store.persist(evidence); store.audit(actor, target, "SYNTHETIC_EVIDENCE_" + type);
    }

    @Transactional
    public void restriction(UUID actor, UUID target, boolean active) {
        store.lockAccounts(List.of(actor, target)); require(actor, Capability.ADMIN_RESTRICTIONS);
        store.queries().createQuery("select r from Restriction r where r.accountId = :id and r.active = true", Restriction.class)
                .setParameter("id", target).getResultList().forEach(r -> r.active = false);
        if (active) {
            Restriction restriction = new Restriction(); restriction.id = UUID.randomUUID(); restriction.accountId = target;
            restriction.active = true; restriction.createdAt = clock.instant(); restriction.reason = "SYNTHETIC_REVIEW"; store.persist(restriction);
        }
        store.audit(actor, target, active ? "RESTRICTED" : "RESTRICTION_REMOVED");
    }

    @Transactional
    public void removeRole(UUID actor, UUID target, String role) {
        store.lockAccounts(List.of(actor, target)); require(actor, Capability.ADMIN_IDENTITY);
        AdminRole parsed;
        try { parsed = AdminRole.valueOf(role); }
        catch (IllegalArgumentException failure) { throw new IdentityFailure("Rol inválido"); }
        Account account = store.account(target);
        if (account.roles.remove(parsed)) { account.securityEpoch++; store.audit(actor, target, "ROLE_REMOVED_" + parsed); }
    }

    @Transactional
    public void reviewGuardian(UUID actor, UUID linkId, long version, boolean verified) {
        IdentityStore.LinkParticipants preliminary = store.linkParticipants(linkId);
        store.lockAccounts(List.of(actor, preliminary.guardianId(), preliminary.minorId()));
        require(actor, Capability.ADMIN_IDENTITY);
        GuardianLink link = store.lock(GuardianLink.class, linkId);
        GuardianService.requireLive(link, version);
        if (link.status != LinkStatus.PENDING || actor.equals(link.guardianId) || actor.equals(link.minorId) || !syntheticEvidence) {
            throw new IdentityFailure("Revisión independiente sintética requerida");
        }
        if (verified && (store.age(link.guardianId) < 18 || store.age(link.minorId) < 0 || store.age(link.minorId) >= 18
                || !store.account(link.guardianId).contactVerified || !store.account(link.minorId).contactVerified
                || store.restricted(link.guardianId) || store.restricted(link.minorId)
                || store.evidence(link.minorId, EvidenceKind.MINOR_DRAFT).isEmpty())) {
            throw new IdentityFailure("Evidencia separada de adulto, menor y consentimiento requerida");
        }
        link.status = verified ? LinkStatus.VERIFIED : LinkStatus.REJECTED;
        link.authorityExpiresAt = verified ? clock.instant().plusSeconds(86400) : null;
        link.provenance = "LOCAL_SYNTHETIC_RELATIONSHIP:" + actor;
        store.audit(actor, link.minorId, verified ? "GUARDIAN_VERIFIED_SYNTHETIC" : "GUARDIAN_REJECTED");
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(UUID actor) {
        require(actor, Capability.ADMIN_AUDIT);
        return store.queries().createQuery("select e from AuditEvent e order by e.occurredAt desc, e.id desc", AuditEvent.class)
                .setMaxResults(100).getResultList().stream().map(e -> new AuditView(e.id, e.actorId, e.subjectId, e.action, e.occurredAt, e.scope)).toList();
    }

    private boolean allowed(UUID actor, Capability capability) {
        return permissions.evaluate(PermissionRequest.self(new AccountId(actor), capability)).allowed();
    }
    private void require(UUID actor, Capability capability) {
        if (!allowed(actor, capability)) { throw new IdentityFailure("Rol, MFA y autenticación reciente requeridos"); }
    }
    public record AccountView(UUID id, boolean contactVerified, int age, boolean restricted, List<String> roles) {}
    public record AuditView(UUID id, UUID actorId, UUID subjectId, String action, Instant occurredAt, String scope) {}
}
