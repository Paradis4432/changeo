package ar.changeo.identity.api;

public record AccountAccessSnapshot(AccountId accountId, boolean contactVerified, String ageStatus,
                                    boolean restricted, boolean sensitivePreference,
                                    boolean adultPreference, String evidenceScope,
                                    String publicEligibility) {}
