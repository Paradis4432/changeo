package ar.changeo.jobs.api;

import ar.changeo.conversations.api.ConversationContext;
import ar.changeo.identity.api.AccountId;
import ar.changeo.moderation.api.ContentReference;
import java.util.*;

public interface JobAccess {
    void createProspect(ConversationContext context);
    JobSnapshot discover(UUID id);
    JobSnapshot lock(UUID id);
    void agree(UUID id, ContentReference agreement, AccountId legalPayer);
    void changePending(UUID id, ContentReference amendment);
    void replaceUnfundedAgreement(UUID jobId, ContentReference expectedOld, ContentReference replacement, AccountId originalPayer);
}
