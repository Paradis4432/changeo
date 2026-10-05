package ar.changeo.marketplace;

import ar.changeo.listings.api.*;
import ar.changeo.moderation.api.ContentReference;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ListingService implements ListingAccess {
    private final MarketplaceReader reader;
    ListingService(MarketplaceReader reader) { this.reader=reader; }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ListingSnapshot currentPublic(UUID id) { return snapshot(reader.currentInTransaction(id,Optional.empty())); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ListingSnapshot requireOpenForResponse(UUID id) { return snapshot(reader.currentInTransaction(id,Optional.empty())); }

    @Override @Transactional(propagation=Propagation.MANDATORY)
    public ListingSnapshot historical(ContentReference reference) { return snapshot(reader.historicalInTransaction(reference)); }

    private static ListingSnapshot snapshot(MarketplaceReader.PublicView view) {
        if (view.type().profile()) { throw MarketplaceStore.unavailable(); }
        return new ListingSnapshot(view.reference(),view.subject(),view.type(),view.details(),view.label());
    }
}
