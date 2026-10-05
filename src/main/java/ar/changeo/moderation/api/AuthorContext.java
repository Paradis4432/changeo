package ar.changeo.moderation.api;

import ar.changeo.identity.api.*;
import java.util.*;

public record AuthorContext(ActorId actor, SubjectId subject, Capability capability, Optional<JobContext> jobContext) {
    public AuthorContext {
        Objects.requireNonNull(actor); Objects.requireNonNull(subject); Objects.requireNonNull(capability); Objects.requireNonNull(jobContext);
    }
    public AuthorContext(ActorId actor,SubjectId subject,Capability capability) { this(actor,subject,capability,Optional.empty()); }
    public PermissionRequest permission() { return new PermissionRequest(Optional.of(actor),Optional.of(subject),capability,jobContext); }
}
