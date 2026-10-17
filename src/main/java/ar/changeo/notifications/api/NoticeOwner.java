package ar.changeo.notifications.api;

import ar.changeo.identity.api.ActorId;
import ar.changeo.moderation.api.*;
import java.util.Set;

public interface NoticeOwner {
    Set<SurfaceKind> noticeKinds();
    void requireNotice(ContentReference reference, ActorId recipient);
}
