package ar.changeo.jobs;

import ar.changeo.agreements.api.AgreementSnapshot;
import ar.changeo.jobs.api.*;
import java.util.Currency;

final class JobFinancialMetadata {
    private JobFinancialMetadata() {}

    static FinancialAgreement agreement(JobSnapshot job, AgreementSnapshot revision) {
        var context = job.conversation();
        var terms = revision.terms().requireComplete(context.mode());
        if (!revision.jobId().equals(job.id()) || !job.agreement().filter(revision.reference()::equals).isPresent()) {
            throw new IllegalStateException("Términos ajenos a la obligación");
        }
        return new FinancialAgreement(job.id(), revision.reference(), context.customer(), context.provider(),
                context.customerActor(), context.providerActor(), job.legalPayer().orElseThrow(), terms.serviceMinor(),
                terms.deliveryMinor(), terms.totalMinor(), Currency.getInstance("ARS"), terms.policyScope(),
                terms.policyVersion(), terms.finalRule(), job.version());
    }
}
