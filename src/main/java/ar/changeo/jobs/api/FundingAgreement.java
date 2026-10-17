package ar.changeo.jobs.api;

import ar.changeo.agreements.api.AgreementTerms;
import ar.changeo.identity.api.*;
import ar.changeo.moderation.api.ContentReference;
import java.util.UUID;

public record FundingAgreement(UUID jobId, ContentReference agreement, SubjectId customer, SubjectId provider,
        ActorId customerActor, ActorId providerActor, AccountId legalPayer, long serviceMinor, long deliveryMinor,
        long totalMinor, AgreementTerms terms, long canonicalVersion) {}
