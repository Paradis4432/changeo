package ar.changeo.jobs.api;

import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ContentReference;
import java.time.Instant;
import java.util.*;

public record JobQualification(UUID jobId, SubjectId provider, SubjectId customer, AccountId originalCustomerPayer,
        ActorId originalProviderActor, Set<AccountId> providerAccounts, Set<AccountId> customerAccounts,
        Optional<UUID> completedEvent, Optional<Instant> completedAt, ContentReference terms,
        Optional<ContentReference> finalDelivery, long serviceMinor, boolean qualified, String reason, String evidenceDigest) {
    public JobQualification { providerAccounts = Set.copyOf(providerAccounts); customerAccounts = Set.copyOf(customerAccounts); }
}
