package ar.changeo.listings.api;

import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ContentReference;
import java.util.Objects;
import java.util.UUID;

public record ListingSelection(ContentReference request, UUID jobId, SubjectId customer,
                               SubjectId provider, ActorId selectingCustomer) {
    public ListingSelection {
        Objects.requireNonNull(request);
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(customer);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(selectingCustomer);
    }
}
