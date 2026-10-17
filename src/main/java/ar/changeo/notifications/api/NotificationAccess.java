package ar.changeo.notifications.api;

import ar.changeo.identity.api.ActorId;
import ar.changeo.moderation.api.ContentReference;
import java.util.Set;

public interface NotificationAccess {
    void enqueue(ContentReference reference, Set<ActorId> recipients);
}
