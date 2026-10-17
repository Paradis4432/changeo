package ar.changeo.conversations;

import ar.changeo.identity.api.JobContext.FulfillmentMode;
import ar.changeo.identity.api.SubjectId;
import ar.changeo.jobs.api.JobSnapshot;
import ar.changeo.moderation.api.ContentReference;
import java.util.Optional;
import java.util.UUID;

public record ConversationDisplay(UUID id, UUID jobId, ContentReference listing, SubjectId customer, SubjectId provider,
        FulfillmentMode mode, JobSnapshot.State state, boolean accepted, boolean normalActions, Optional<CapturedListing> capture) {
    public String listingTitle() { return capture.map(CapturedListing::title).orElse("Conversación privada"); }
    public String listingSnapshot() { return capture.map(CapturedListing::snapshot).orElse(""); }
    public record CapturedListing(String title, String snapshot, String label) {}
}
