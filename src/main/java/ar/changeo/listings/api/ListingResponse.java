package ar.changeo.listings.api;

import ar.changeo.identity.api.ActorId;
import java.util.Objects;

public record ListingResponse(ListingSnapshot listing, ActorId publisher, String digest, long version) {
    public ListingResponse {
        Objects.requireNonNull(listing);
        Objects.requireNonNull(publisher);
        Objects.requireNonNull(digest);
    }
}
