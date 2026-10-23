package ar.changeo.moderation.api;

import ar.changeo.files.api.*;
import ar.changeo.identity.api.AccountId;
import ar.changeo.identity.api.ActorId;
import ar.changeo.identity.api.PermissionRequest;
import ar.changeo.moderation.ReviewInput;
import java.util.Optional;
import java.util.Set;

public interface ContentOwner {
    Set<SurfaceKind> kinds();
    boolean owns(ContentReference reference);
    default Optional<String> caseOrigin(ContentReference reference) { return Optional.empty(); }
    default Set<AccountId> identityParticipants(ContentReference reference) { return Set.of(); }
    default Set<ContentReference> approvalDependencies(ContentReference reference) { return Set.of(); }
    default void prepareProtection(PermissionRequest permission, AuthorContext author, ContentReference reference, Set<AccountId> declaredIdentityAccounts) {}
    default void prepareActivation(ContentReference reference) {}
    default ActivationLifecycle activationLifecycle(ContentReference reference) { return ActivationLifecycle.DELIVERY; }
    ReviewInput lockRevision(AuthorContext author,ContentReference reference);
    boolean current(ContentReference reference);
    boolean reportable(ContentReference reference, ActorId reporter, String effectiveLabel);
    long activate(ContentReference reference);
    void recall(ContentReference reference);
    void requireAttachment(FileReference reference,FileAccess.Purpose purpose);
    enum ActivationLifecycle { DELIVERY, REAFFIRMATION }
}
