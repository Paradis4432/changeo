package ar.changeo.jobs.api;

import ar.changeo.agreements.api.AgreementTerms;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ContentReference;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record FinancialAgreement(UUID jobId, ContentReference agreement, SubjectId customer, SubjectId provider,
        ActorId customerActor, ActorId providerActor, AccountId legalPayer, long serviceMinor, long deliveryMinor,
        long totalMinor, Currency currency, AgreementTerms.PolicyScope policyScope,
        AgreementTerms.PolicyVersion policyVersion, AgreementTerms.FinalRule finalRule, long canonicalVersion) {
    public FinancialAgreement {
        Objects.requireNonNull(jobId); Objects.requireNonNull(agreement); Objects.requireNonNull(customer);
        Objects.requireNonNull(provider); Objects.requireNonNull(customerActor); Objects.requireNonNull(providerActor);
        Objects.requireNonNull(legalPayer); Objects.requireNonNull(currency); Objects.requireNonNull(policyScope);
        Objects.requireNonNull(policyVersion); Objects.requireNonNull(finalRule);
        if (serviceMinor < 0 || deliveryMinor < 0 || totalMinor != Math.addExact(serviceMinor, deliveryMinor)
                || !currency.equals(Currency.getInstance("ARS")) || canonicalVersion < 0
                || !customerActor.value().equals(legalPayer.value())) { throw new IllegalArgumentException("Acuerdo financiero incompatible"); }
    }
}
