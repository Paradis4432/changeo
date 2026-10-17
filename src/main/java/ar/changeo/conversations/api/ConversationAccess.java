package ar.changeo.conversations.api;

import ar.changeo.identity.api.*;
import java.util.*;

public interface ConversationAccess {
    ConversationContext discover(UUID threadId);
    Set<AccountId> participants(UUID threadId);
    ConversationContext lock(UUID threadId);
    PermissionDecision protect(UUID threadId, ActorId actor, SubjectId subject, Capability capability);
    PermissionDecision authorize(UUID threadId, ActorId actor, SubjectId subject, Capability capability);
    void requireCurrent(ConversationContext context);
    void requireEligible(ConversationContext context);
    boolean eligibleForLoyaltyFacts(ConversationContext context, Set<String> approvalLabels);
}
