package ar.changeo.identity.api;

import java.util.Optional;

public record PermissionDecision(boolean allowed, String reason, Optional<AccountId> actingAccountId,
                                 Optional<AccountId> subjectAccountId, Optional<AccountId> legalPayerId,
                                 String evidenceScope) {}
