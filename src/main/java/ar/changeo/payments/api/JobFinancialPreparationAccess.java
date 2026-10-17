package ar.changeo.payments.api;

import ar.changeo.jobs.api.*;

public interface JobFinancialPreparationAccess {
    JobMoneyStatus prepareViewForUpdate(JobFinancialFacts facts);
    JobMoneyStatus prepareFundingForUpdate(FundingAgreement agreement, FundingSelection selection);
    JobMoneyStatus prepareReleaseForUpdate(JobFinancialFacts facts, FinalReleaseRequest request);
    JobMoneyStatus prepareSettlementForUpdate(JobFinancialFacts facts, SettlementPreparation request);
}
