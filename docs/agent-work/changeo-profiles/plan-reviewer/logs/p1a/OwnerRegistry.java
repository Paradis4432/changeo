package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class OwnerRegistry {
    private final Map<SurfaceKind,List<ContentOwner>> owners;
    public OwnerRegistry(List<ContentOwner> installed) {
        var owners=new EnumMap<SurfaceKind,List<ContentOwner>>(SurfaceKind.class);
        for(var owner:installed) { for(var kind:owner.kinds()) { owners.computeIfAbsent(kind,unused->new ArrayList<>()).add(owner); } }
        owners.replaceAll((kind,handlers)->List.copyOf(handlers));
        this.owners=Map.copyOf(owners);
    }
    public ContentOwner require(ContentReference reference) {
        var matches=owners.getOrDefault(reference.ownerKind(),List.of()).stream().filter(owner->owner.owns(reference)).toList();
        if(matches.size()!=1) { throw ModerationStore.unavailable(); } return matches.getFirst();
    }
}
