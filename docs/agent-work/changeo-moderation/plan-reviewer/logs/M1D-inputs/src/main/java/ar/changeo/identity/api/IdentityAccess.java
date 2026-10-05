package ar.changeo.identity.api;

public interface IdentityAccess {
    PermissionDecision decide(PermissionRequest request);
    PermissionDecision requireForUpdate(PermissionRequest request);
    AccountAccessSnapshot snapshot(AccountId accountId);
}
