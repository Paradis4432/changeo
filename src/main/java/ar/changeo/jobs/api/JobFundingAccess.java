package ar.changeo.jobs.api;

import java.util.UUID;

public interface JobFundingAccess {
    FundingAgreement requireFundable(UUID jobId, UUID agreementRevisionId);
    FundingAgreement requireFundable(UUID jobId, UUID agreementRevisionId, ar.changeo.payments.api.FundingSelection selection);
}
