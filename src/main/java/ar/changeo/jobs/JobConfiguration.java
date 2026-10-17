package ar.changeo.jobs;

import ar.changeo.payments.api.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Configuration
class JobConfiguration {
    @Bean @ConditionalOnMissingBean(JobMoneyStatusAccess.class)
    @ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "false", matchIfMissing = true)
    JobMoneyStatusAccess pendingJobMoney() {
        return (job, agreement) -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Se requiere la transacción canónica del trabajo"); }
            return new JobMoneyStatus(job, agreement, JobMoneyStatus.Funding.INTEGRATION_PENDING,
                    JobMoneyStatus.Release.INTEGRATION_PENDING, JobMoneyStatus.BankPayout.INTEGRATION_PENDING);
        };
    }
    @Bean @ConditionalOnMissingBean(JobFinancialPreparationAccess.class)
    @ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "false", matchIfMissing = true)
    JobFinancialPreparationAccess pendingFinancialPreparation(JobMoneyStatusAccess money) {
        return new JobFinancialPreparationAccess() {
            @Override public JobMoneyStatus prepareViewForUpdate(ar.changeo.jobs.api.JobFinancialFacts facts) {
                return money.currentForUpdate(facts.agreement().jobId(), facts.agreement().agreement());
            }
            @Override public JobMoneyStatus prepareFundingForUpdate(ar.changeo.jobs.api.FundingAgreement agreement, FundingSelection selection) {
                return money.currentForUpdate(agreement.jobId(), agreement.agreement());
            }
            @Override public JobMoneyStatus prepareReleaseForUpdate(ar.changeo.jobs.api.JobFinancialFacts facts, FinalReleaseRequest request) {
                return money.currentForUpdate(facts.agreement().jobId(), facts.agreement().agreement());
            }
            @Override public JobMoneyStatus prepareSettlementForUpdate(ar.changeo.jobs.api.JobFinancialFacts facts, SettlementPreparation request) {
                return money.currentForUpdate(facts.agreement().jobId(), facts.agreement().agreement());
            }
        };
    }
    @Bean @ConditionalOnMissingBean(JobDisputeAccess.class)
    @ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "false", matchIfMissing = true)
    JobDisputeAccess pendingFinancialDispute() { return (facts, command, actor, dispute) -> requireTransaction(); }
    @Bean @ConditionalOnMissingBean(JobAgreementChangeAccess.class)
    @ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "false", matchIfMissing = true)
    JobAgreementChangeAccess pendingAgreementChange(JobMoneyStatusAccess money) {
        return new JobAgreementChangeAccess() {
            @Override public boolean prepareReplacementForUpdate(ar.changeo.jobs.api.FundingAgreement original, ar.changeo.jobs.api.FundingAgreement replacement) {
                requireTransaction(); money.currentForUpdate(original.jobId(), original.agreement()).requireExact(original.jobId(), original.agreement()); return false;
            }
            @Override public void replaceForUpdate(ar.changeo.jobs.api.FundingAgreement original, ar.changeo.jobs.api.FundingAgreement replacement) {
                throw new IllegalStateException("Integración financiera pendiente");
            }
        };
    }
    @Bean @ConditionalOnMissingBean(JobSettlementStatusAccess.class)
    @ConditionalOnProperty(name = "changeo.synthetic-payments", havingValue = "false", matchIfMissing = true)
    JobSettlementStatusAccess pendingSettlementStatus() {
        return (job, agreement, settlement) -> { requireTransaction(); return new JobSettlementStatus(false, false, true, agreement); };
    }
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("Se requiere la transacción canónica del trabajo"); }
    }
}
