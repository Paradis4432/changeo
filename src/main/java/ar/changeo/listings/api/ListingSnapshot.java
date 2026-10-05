package ar.changeo.listings.api;

import ar.changeo.identity.api.AccountId;
import ar.changeo.marketplace.MarketplaceDetails;
import ar.changeo.marketplace.PresenceType;
import ar.changeo.moderation.api.ContentReference;

public record ListingSnapshot(ContentReference reference, AccountId subject, PresenceType type, MarketplaceDetails details, String label) {}
