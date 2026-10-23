package ar.changeo.conversations.api;

import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ContentReference;
import java.util.*;

public interface ConversationAccess {
    ConversationContext discover(UUID threadId);
    Set<AccountId> participants(UUID threadId);
    ConversationContext lock(UUID threadId);
    PermissionDecision protect(UUID threadId, ActorId actor, SubjectId subject, Capability capability);
    PermissionDecision authorize(UUID threadId, ActorId actor, SubjectId subject, Capability capability);
    void requireCurrent(ConversationContext context);
    void requireEligible(ConversationContext context);
    Optional<SubjectId> noticeSubject(UUID threadId, ActorId recipient);
    void requireReportable(UUID threadId, ActorId reporter, String effectiveLabel, Optional<ContentReference> terms);
    boolean eligibleForLoyaltyFacts(ConversationContext context, Set<String> approvalLabels);
}
