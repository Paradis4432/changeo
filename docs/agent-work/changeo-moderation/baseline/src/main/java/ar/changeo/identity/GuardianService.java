package ar.changeo.identity;

import ar.changeo.identity.api.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuardianService {
    private final IdentityStore store;
    private final PermissionService permissions;

    GuardianService(IdentityStore store, PermissionService permissions) { this.store = store; this.permissions = permissions; }

    @Transactional
    public UUID request(UUID actor, UUID guardian, UUID minor) {
        if (!actor.equals(guardian) && !actor.equals(minor)) { throw new IdentityFailure("Relación propia requerida"); }
        if (guardian.equals(minor)) { throw new IdentityFailure("Participantes distintos requeridos"); }
        store.lockAccounts(List.of(guardian, minor));
        if (store.age(guardian) < 18 || store.age(minor) < 0 || store.age(minor) >= 18
                || !store.account(guardian).contactVerified || !store.account(minor).contactVerified
                || store.restricted(guardian) || store.restricted(minor)) {
            throw new IdentityFailure("Edad/contacto sintético pendiente o restringido");
        }
        boolean exists = !store.queries().createQuery("select l.id from GuardianLink l where l.minorId = :id and l.status in :states", UUID.class)
                .setParameter("id", minor).setParameter("states", List.of(LinkStatus.PENDING, LinkStatus.VERIFIED)).getResultList().isEmpty();
        if (exists) { throw new IdentityFailure("Ya existe una relación pendiente o verificada"); }
        GuardianLink link = new GuardianLink(); link.id = UUID.randomUUID(); link.guardianId = guardian;
        link.minorId = minor; link.status = LinkStatus.PENDING; store.persist(link);
        store.audit(actor, minor, "GUARDIAN_REQUESTED"); return link.id;
    }

    @Transactional(readOnly = true)
    public List<LinkView> own(UUID actor) {
        return store.queries().createQuery("select l from GuardianLink l where l.guardianId = :actor or l.minorId = :actor order by l.id", GuardianLink.class)
                .setParameter("actor", actor).getResultList().stream().map(GuardianService::view).toList();
    }

    @Transactional
    public void revoke(UUID actor, UUID linkId, long expectedVersion) {
        IdentityStore.LinkParticipants preliminary = store.linkParticipants(linkId);
        List<UUID> participants = List.of(actor, preliminary.guardianId(), preliminary.minorId());
        store.lockAccounts(participants);
        GuardianLink link = store.lock(GuardianLink.class, linkId);
        if (!actor.equals(link.guardianId) && !actor.equals(link.minorId)) {
            requireAdmin(actor);
        }
        requireLive(link, expectedVersion);
        link.status = LinkStatus.REVOKED;
        store.queries().createQuery("select c from JobConsent c where c.linkId = :id order by c.id", JobConsent.class)
                .setParameter("id", link.id).setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getResultList().forEach(consent -> consent.status = ConsentStatus.REVOKED);
        store.audit(actor, link.minorId, "GUARDIAN_REVOKED");
    }

    @Transactional
    public UUID grant(UUID actor, UUID linkId, UUID syntheticJob) {
        IdentityStore.LinkParticipants preliminary = store.linkParticipants(linkId);
        store.lockAccounts(List.of(actor, preliminary.guardianId(), preliminary.minorId()));
        GuardianLink link = store.lock(GuardianLink.class, linkId);
        PermissionRequest request = new PermissionRequest(Optional.of(new ActorId(actor)), Optional.of(new SubjectId(link.minorId)),
                Capability.GRANT_IN_PERSON_CONSENT, Optional.of(new JobContext(syntheticJob, JobContext.FulfillmentMode.LOCAL)));
        if (!actor.equals(link.guardianId) || link.status != LinkStatus.VERIFIED || !permissions.evaluate(request).allowed()) {
            throw new IdentityFailure("Autoridad verificada vigente requerida");
        }
        if (store.consent(link.id, syntheticJob)) { throw new IdentityFailure("Ya existe consentimiento activo"); }
        JobConsent consent = new JobConsent(); consent.id = UUID.randomUUID(); consent.linkId = link.id;
        consent.jobId = syntheticJob; consent.mode = "LOCAL"; consent.status = ConsentStatus.ACTIVE;
        store.persist(consent); store.audit(actor, link.minorId, "SYNTHETIC_CONSENT_GRANTED"); return consent.id;
    }

    @Transactional
    public void revokeConsent(UUID actor, UUID linkId, UUID consentId) {
        IdentityStore.LinkParticipants preliminary = store.linkParticipants(linkId);
        store.lockAccounts(List.of(actor, preliminary.guardianId(), preliminary.minorId()));
        GuardianLink link = store.lock(GuardianLink.class, linkId);
        JobConsent consent = store.lock(JobConsent.class, consentId);
        if (!actor.equals(link.guardianId) || !consent.linkId.equals(link.id) || consent.status != ConsentStatus.ACTIVE) {
            throw new IdentityFailure("Consentimiento propio activo requerido");
        }
        consent.status = ConsentStatus.REVOKED; store.audit(actor, link.minorId, "SYNTHETIC_CONSENT_REVOKED");
    }

    @Transactional(readOnly = true)
    public List<ConsentView> consents(UUID actor) {
        return store.queries().createQuery("select c from JobConsent c, GuardianLink l where c.linkId = l.id and (l.guardianId = :id or l.minorId = :id) order by c.id", JobConsent.class)
                .setParameter("id", actor).getResultList().stream().map(c -> new ConsentView(c.id, c.linkId, c.jobId, c.status.name())).toList();
    }

    private void requireAdmin(UUID actor) {
        if (!permissions.evaluate(PermissionRequest.self(new AccountId(actor), Capability.ADMIN_IDENTITY)).allowed()) {
            throw new IdentityFailure("Administración de identidad requerida");
        }
    }

    static void requireLive(GuardianLink link, long version) {
        if (link.version != version || (link.status != LinkStatus.PENDING && link.status != LinkStatus.VERIFIED)) {
            throw new IdentityFailure("Relación terminal o revisión desactualizada");
        }
    }

    static LinkView view(GuardianLink link) { return new LinkView(link.id, link.guardianId, link.minorId, link.status.name(), link.version); }
    public record LinkView(UUID id, UUID guardianId, UUID minorId, String status, long version) {}
    public record ConsentView(UUID id, UUID linkId, UUID jobId, String status) {}
}
