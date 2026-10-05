package ar.changeo.moderation.api;

import ar.changeo.files.api.FileReference;
import java.util.List;

public interface ModerationAccess {
    Approval submitForReview(AuthorContext author, ContentReference reference, String text, String declaredLabel, List<FileReference> attachments);
    Approval approval(ContentReference reference);
    record Approval(ContentReference reference, String digest, String state, String label, String reason) {}
}
