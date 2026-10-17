package ar.changeo.payments.api;

import ar.changeo.jobs.api.FinancialAgreement;

public record JobLoyaltyMoneyFacts(FinancialAgreement agreement, boolean qualified, String reason, long fundedMinor,
        long releasedMinor, long bankPaidMinor, long refundedMinor, long deficitMinor, long version) {}
