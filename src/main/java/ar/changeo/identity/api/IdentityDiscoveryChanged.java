package ar.changeo.identity.api;

import ar.changeo.identity.IdentityFailure;

public final class IdentityDiscoveryChanged extends IdentityFailure {
    public IdentityDiscoveryChanged() { super("La autoridad cambió. Reintentar en una transacción nueva"); }
}
