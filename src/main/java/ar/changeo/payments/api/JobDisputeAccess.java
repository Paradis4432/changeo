package ar.changeo.payments.api;

import ar.changeo.identity.api.ActorId;
import ar.changeo.jobs.api.JobFinancialFacts;
import java.util.UUID;

public interface JobDisputeAccess {
    void pauseForUpdate(JobFinancialFacts facts, UUID commandId, ActorId actor, boolean dispute);
}
