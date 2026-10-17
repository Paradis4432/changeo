package ar.changeo.moderation;

import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class OwnerProtection {
    private final OwnerRegistry owners;
    private final ParticipantIdentityAccess identity;

    OwnerProtection(OwnerRegistry owners, ParticipantIdentityAccess identity) {
        this.owners = owners;
        this.identity = identity;
    }

    ProtectedRevision protect(PermissionRequest permission, AuthorContext author, ContentReference reference) {
        ContentOwner owner = owners.require(reference);
        Set<AccountId> participants = participants(owner, permission, author, reference);
        Set<ContentReference> dependencies = dependencies(owner, reference);
        identity.requireForUpdate(permission, participants);
        if (!participants.equals(participants(owner, permission, author, reference))) { throw ModerationStore.unavailable(); }
        owner.prepareProtection(permission, author, reference, participants);
        ReviewInput canonical = owner.lockRevision(author, reference);
        if (!participants.equals(participants(owner, permission, author, reference))
                || !dependencies.equals(dependencies(owner, reference))) {
            throw ModerationStore.unavailable();
        }
        return new ProtectedRevision(owner, canonical, dependencies);
    }

    private Set<AccountId> participants(ContentOwner owner, PermissionRequest permission, AuthorContext author, ContentReference reference) {
        Set<AccountId> accounts = new HashSet<>(owner.identityParticipants(reference));
        permission.actorId().ifPresent(actor -> accounts.add(new AccountId(actor.value())));
        permission.subjectId().ifPresent(subject -> accounts.add(new AccountId(subject.value())));
        Set<SubjectId> subjects = new HashSet<>(); permission.subjectId().ifPresent(subjects::add);
        if (permission.equals(author.permission())) {
            accounts.add(new AccountId(author.actor().value())); accounts.add(new AccountId(author.subject().value()));
            subjects.add(author.subject());
        }
        if (!subjects.isEmpty()) { identity.controllers(Set.copyOf(subjects)).values().forEach(actor -> accounts.add(new AccountId(actor.value()))); }
        if (accounts.size() > 64) { throw ModerationStore.unavailable(); }
        return Set.copyOf(accounts);
    }

    private Set<ContentReference> dependencies(ContentOwner owner, ContentReference reference) {
        Set<ContentReference> dependencies = Set.copyOf(owner.approvalDependencies(reference));
        if (dependencies.size() > 8 || dependencies.contains(reference)) {
            throw ModerationStore.unavailable();
        }
        Set<ContentReference> visiting = new HashSet<>(Set.of(reference));
        Set<ContentReference> visited = new HashSet<>();
        for (ContentReference dependency : dependencies) { visit(dependency, dependencies, visiting, visited); }
        return dependencies;
    }

    private void visit(ContentReference reference, Set<ContentReference> declared, Set<ContentReference> visiting, Set<ContentReference> visited) {
        if (visiting.contains(reference)) { throw ModerationStore.unavailable(); }
        if (visited.contains(reference)) { return; }
        visiting.add(reference);
        var nested = Set.copyOf(owners.require(reference).approvalDependencies(reference));
        if (!declared.containsAll(nested)) { throw ModerationStore.unavailable(); }
        for (var dependency : nested) { visit(dependency, declared, visiting, visited); }
        visiting.remove(reference); visited.add(reference);
    }

    record ProtectedRevision(ContentOwner owner, ReviewInput canonical, Set<ContentReference> dependencies) {
        Set<ContentReference> deliveryReferences() {
            Set<ContentReference> references = new HashSet<>(dependencies);
            references.add(canonical.reference());
            return Set.copyOf(references);
        }
    }
}
