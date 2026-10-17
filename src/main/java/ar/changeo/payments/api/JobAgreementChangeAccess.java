package ar.changeo.payments.api;

import ar.changeo.jobs.api.FundingAgreement;

public interface JobAgreementChangeAccess {
    boolean prepareReplacementForUpdate(FundingAgreement original, FundingAgreement replacement);
    void replaceForUpdate(FundingAgreement original, FundingAgreement replacement);
}
