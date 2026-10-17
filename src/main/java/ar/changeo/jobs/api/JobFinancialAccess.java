package ar.changeo.jobs.api;

import ar.changeo.identity.api.SubjectId;
import ar.changeo.payments.api.*;
import java.util.UUID;

public interface JobFinancialAccess {
    JobFinancialFacts requireView(UUID jobId, SubjectId side);
    JobFinancialFacts requireRelease(UUID jobId, UUID agreementRevisionId, SubjectId side, FinalReleaseRequest request);
    JobFinancialFacts requireSettlement(UUID jobId, UUID agreementRevisionId, SubjectId side, SettlementPreparation request);
}
