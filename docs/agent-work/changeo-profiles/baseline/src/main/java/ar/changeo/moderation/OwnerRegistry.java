package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class OwnerRegistry {
    private final Map<SurfaceKind,ContentOwner> owners;
    public OwnerRegistry(List<ContentOwner> installed) {
        var owners=new EnumMap<SurfaceKind,ContentOwner>(SurfaceKind.class);
        for(var owner:installed) { for(var kind:owner.kinds()) { if(owners.putIfAbsent(kind,owner)!=null) { throw new IllegalStateException("Duplicate canonical owner"); } } }
        this.owners=Map.copyOf(owners);
    }
    public ContentOwner require(ContentReference reference) {
        var owner=owners.get(reference.ownerKind()); if(owner==null) { throw ModerationStore.unavailable(); } return owner;
    }
}
