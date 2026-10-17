package ar.changeo.listings.api;

import ar.changeo.moderation.api.ContentReference;

public interface ListingResponseAccess {
    ListingResponse prepareResponse(ContentReference expectedReference);
    ListingResponse requireOpen(ContentReference expectedReference);
    void selectRequestProvider(ListingSelection selection);
}
