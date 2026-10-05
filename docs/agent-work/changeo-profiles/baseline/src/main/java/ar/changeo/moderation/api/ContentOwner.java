package ar.changeo.moderation.api;

import ar.changeo.files.api.*;
import ar.changeo.moderation.ReviewInput;
import java.util.Set;

public interface ContentOwner {
    Set<SurfaceKind> kinds();
    ReviewInput lockRevision(AuthorContext author,ContentReference reference);
    boolean current(ContentReference reference);
    boolean reportable(ContentReference reference);
    long activate(ContentReference reference);
    void recall(ContentReference reference);
    void requireAttachment(FileReference reference,FileAccess.Purpose purpose);
}
