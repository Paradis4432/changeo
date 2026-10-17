package ar.changeo.moderation.api;

import ar.changeo.files.api.FileReference;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

public interface ModerationAccess {
    Approval submitForReview(AuthorContext author, ContentReference reference, String text, String declaredLabel, List<FileReference> attachments);
    Approval approval(ContentReference reference);
    Approval approvalForUpdate(ContentReference reference);
    Optional<Approval> findApprovalForUpdate(ContentReference reference);
    Map<ContentReference, Approval> approvalsForUpdate(Set<ContentReference> references);
    record Approval(ContentReference reference, String digest, String state, String label, String reason) {}
}
