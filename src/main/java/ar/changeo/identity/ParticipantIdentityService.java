package ar.changeo.identity;

import ar.changeo.identity.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ParticipantIdentityService implements ParticipantIdentityAccess {
    private static final int MAX_PARTICIPANTS = 64;
    private final IdentityStore store;
    private final PermissionService permissions;
    private final Clock clock;
    private final TransactionTemplate participating;

    ParticipantIdentityService(IdentityStore store, PermissionService permissions, Clock clock,
                               PlatformTransactionManager transactions) {
        this.store = store;
        this.permissions = permissions;
        this.clock = clock;
        participating = new TransactionTemplate(transactions);
        participating.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<SubjectId, ActorId> controllers(Set<SubjectId> subjects) {
        Set<SubjectId> requested = Set.copyOf(subjects);
        if (requested.isEmpty() || requested.size() > MAX_PARTICIPANTS) {
            throw new IdentityFailure("Participantes fuera del límite local");
        }
        Map<SubjectId, ActorId> result = new HashMap<>();
        for (SubjectId subject : requested) {
            store.account(subject.value());
            int age = store.age(subject.value());
            if (age >= 18) {
                result.put(subject, new ActorId(subject.value()));
            } else if (age >= 0) {
                store.currentLink(subject.value())
                        .filter(link -> link.authorityExpiresAt != null && link.authorityExpiresAt.isAfter(clock.instant()))
                        .ifPresent(link -> result.put(subject, new ActorId(link.guardianId)));
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public PermissionDecision requireForUpdate(PermissionRequest request, Set<AccountId> participants) {
        return participating.execute(status -> {
            Set<AccountId> requested = Set.copyOf(participants);
            if (requested.size() > MAX_PARTICIPANTS || request.actorId().isEmpty() || request.subjectId().isEmpty()) {
                throw new IdentityFailure("Una acción protegida requiere participantes autenticados y acotados");
            }
            Set<UUID> complete = new HashSet<>();
            requested.forEach(account -> complete.add(account.value()));
            complete.add(request.actorId().orElseThrow().value());
            complete.add(request.subjectId().orElseThrow().value());
            if (complete.size() > MAX_PARTICIPANTS) {
                throw new IdentityFailure("Participantes fuera del límite local");
            }
            store.lockAccounts(complete);
            PermissionDecision decision = permissions.evaluate(request);
            if (!decision.allowed()) {
                throw new IdentityFailure(decision.reason());
            }
            return decision;
        });
    }
}
