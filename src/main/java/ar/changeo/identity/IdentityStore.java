package ar.changeo.identity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
class IdentityStore {
    private final EntityManager entityManager;
    private final Clock clock;

    IdentityStore(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    Account account(UUID id) {
        Account account = entityManager.find(Account.class, id);
        if (account == null) { throw new IdentityFailure("Cuenta no disponible"); }
        return account;
    }

    Optional<Account> byContact(String contact) {
        return entityManager.createQuery("select a from Account a where a.contact = :contact", Account.class)
                .setParameter("contact", contact).getResultStream().findFirst();
    }

    void lockAccounts(Collection<UUID> requested) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IdentityFailure("Se requiere una transacción del propietario");
        }
        Map<UUID, LinkIdentity> before = activeLinks(requested);
        Set<UUID> complete = new TreeSet<>(Comparator.comparing(UUID::toString));
        complete.addAll(requested);
        before.values().forEach(link -> complete.add(link.guardianId()));
        for (UUID id : complete) {
            List<?> locked = entityManager.createNativeQuery("select id from identity_account where id = :id for update")
                    .setParameter("id", id).getResultList();
            if (locked.isEmpty()) { throw new IdentityFailure("Cuenta no disponible"); }
            Account account = entityManager.find(Account.class, id);
            entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        }
        Map<UUID, LinkIdentity> after = activeLinks(requested);
        if (!before.equals(after)) {
            throw new IdentityFailure("La autoridad cambió. Reintentar en una transacción nueva");
        }
        after.values().stream().map(LinkIdentity::id).distinct().sorted(Comparator.comparing(UUID::toString))
                .forEach(id -> {
                    GuardianLink link = entityManager.find(GuardianLink.class, id);
                    entityManager.refresh(link, LockModeType.PESSIMISTIC_WRITE);
                });
        List<UUID> linkIds = after.values().stream().map(LinkIdentity::id).toList();
        if (!linkIds.isEmpty()) {
            entityManager.createQuery("select c from JobConsent c where c.linkId in :ids order by c.id", JobConsent.class)
                    .setParameter("ids", linkIds).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList()
                    .forEach(consent -> entityManager.refresh(consent, LockModeType.PESSIMISTIC_WRITE));
        }
    }

    private Map<UUID, LinkIdentity> activeLinks(Collection<UUID> ids) {
        Map<UUID, LinkIdentity> links = new HashMap<>();
        if (ids.isEmpty()) { return links; }
        entityManager.createQuery("select l.id, l.minorId, l.guardianId from GuardianLink l where l.minorId in :ids and l.status in :states", Object[].class)
                .setParameter("ids", ids).setParameter("states", List.of(LinkStatus.PENDING, LinkStatus.VERIFIED))
                .getResultList().forEach(row -> links.put((UUID) row[1], new LinkIdentity((UUID) row[0], (UUID) row[2])));
        return links;
    }

    LinkParticipants linkParticipants(UUID linkId) {
        List<Object[]> rows = entityManager.createQuery("select l.guardianId, l.minorId from GuardianLink l where l.id = :id", Object[].class)
                .setParameter("id", linkId).getResultList();
        if (rows.size() != 1) { throw new IdentityFailure("Relación no disponible"); }
        return new LinkParticipants((UUID) rows.getFirst()[0], (UUID) rows.getFirst()[1]);
    }

    record LinkParticipants(UUID guardianId, UUID minorId) {}

    Optional<GuardianLink> currentLink(UUID minor) {
        return entityManager.createQuery("select l from GuardianLink l where l.minorId = :id and l.status = :state", GuardianLink.class)
                .setParameter("id", minor).setParameter("state", LinkStatus.VERIFIED).getResultStream().findFirst();
    }

    Optional<Evidence> evidence(UUID account, EvidenceKind kind) {
        return entityManager.createQuery("select e from Evidence e where e.accountId = :id and e.kind = :kind and e.expiresAt > :now order by e.ordinal desc", Evidence.class)
                .setParameter("id", account).setParameter("kind", kind).setParameter("now", clock.instant())
                .getResultStream().findFirst();
    }

    int age(UUID id) { return evidence(id, EvidenceKind.AGE).map(e -> e.age).orElse(-1); }

    boolean restricted(UUID id) {
        return !entityManager.createQuery("select r.id from Restriction r where r.accountId = :id and r.active = true", UUID.class)
                .setParameter("id", id).setMaxResults(1).getResultList().isEmpty();
    }

    boolean consent(UUID link, UUID job) {
        return !entityManager.createQuery("select c.id from JobConsent c where c.linkId = :link and c.jobId = :job and c.mode = 'LOCAL' and c.status = :status", UUID.class)
                .setParameter("link", link).setParameter("job", job).setParameter("status", ConsentStatus.ACTIVE)
                .setMaxResults(1).getResultList().isEmpty();
    }

    <T> T find(Class<T> type, UUID id) {
        T value = entityManager.find(type, id);
        if (value == null) { throw new IdentityFailure("Registro no disponible"); }
        return value;
    }

    <T> T lock(Class<T> type, UUID id) {
        T value = find(type, id);
        entityManager.refresh(value, LockModeType.PESSIMISTIC_WRITE);
        return value;
    }

    <T> void persist(T value) { entityManager.persist(value); }
    EntityManager queries() { return entityManager; }

    void audit(UUID actor, UUID subject, String action) {
        AuditEvent event = new AuditEvent();
        event.id = UUID.randomUUID(); event.actorId = actor; event.subjectId = subject;
        event.action = action; event.occurredAt = clock.instant(); event.scope = "SANDBOX_SYNTHETIC";
        persist(event);
    }

    private record LinkIdentity(UUID id, UUID guardianId) {}
}
