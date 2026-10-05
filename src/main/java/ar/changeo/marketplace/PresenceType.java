package ar.changeo.marketplace;

import ar.changeo.identity.api.Capability;
import ar.changeo.moderation.api.SurfaceKind;

public enum PresenceType {
    CUSTOMER_PROFILE, PROVIDER_PROFILE, OFFER, REQUEST;

    public boolean profile() { return this == CUSTOMER_PROFILE || this == PROVIDER_PROFILE; }
    public boolean provider() { return this == PROVIDER_PROFILE || this == OFFER; }
    public Capability draftCapability() { return provider() ? Capability.DRAFT_OFFER : Capability.DRAFT_REQUEST; }
    public Capability publishCapability() { return provider() ? Capability.NEW_PROVIDER_PARTICIPATION : Capability.NEW_REQUEST; }
    public SurfaceKind surface() { return profile() ? SurfaceKind.PROFILE_BIO : this == OFFER ? SurfaceKind.OFFER_BODY : SurfaceKind.REQUEST_BODY; }
    public String label() { return switch (this) { case CUSTOMER_PROFILE -> "Perfil de cliente"; case PROVIDER_PROFILE -> "Perfil de proveedor"; case OFFER -> "Ofrezco"; case REQUEST -> "Necesito"; }; }
}
