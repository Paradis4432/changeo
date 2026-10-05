package ar.changeo.listings.api;

import ar.changeo.moderation.api.ContentReference;
import java.util.UUID;

public interface ListingAccess {
    ListingSnapshot currentPublic(UUID listingId);
    ListingSnapshot requireOpenForResponse(UUID listingId);
    ListingSnapshot historical(ContentReference reference);
}
