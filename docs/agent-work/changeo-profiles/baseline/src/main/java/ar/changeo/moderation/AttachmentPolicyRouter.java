package ar.changeo.moderation;

import ar.changeo.files.api.*;
import org.springframework.stereotype.Component;

@Component
class AttachmentPolicyRouter implements AttachmentAccessPolicy {
    private final OwnerRegistry owners;
    AttachmentPolicyRouter(OwnerRegistry owners) { this.owners=owners; }
    @Override public void requireCurrentAccess(FileReference reference,FileAccess.Purpose purpose) { owners.require(reference.content()).requireAttachment(reference,purpose); }
}
