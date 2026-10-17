package ar.changeo.conversations.api;

import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ContentReference;
import java.util.UUID;

public record ConversationContext(UUID id, UUID jobId, ContentReference listing, String listingDigest,
        String listingLabel, String listingTitle, String listingSnapshot, SubjectId customer, SubjectId provider,
        ActorId customerActor, ActorId providerActor, JobContext.FulfillmentMode mode, long version) {
    public JobContext jobContext() { return new JobContext(jobId, mode); }
    public boolean participant(SubjectId subject) { return customer.equals(subject) || provider.equals(subject); }
    public ActorId historicalActor(SubjectId subject) {
        if (customer.equals(subject)) { return customerActor; }
        if (provider.equals(subject)) { return providerActor; }
        throw new IllegalArgumentException("Parte ajena a la conversación");
    }
}
