package ar.changeo.payments.api;

import java.util.UUID;
import java.util.List;
import java.util.Map;

public interface SubscriptionBillingAccess {
    BillingTargets discoverTargets(BillingBinding binding);
    PreparedBillingCharge prepareForUpdate(BillingChargeRequest request);
    void cancelForUpdate(BillingBinding binding, UUID command);
    BillingFinancialStatus requireStatusForUpdate(BillingBinding binding);
    Map<BillingBinding, BillingFinancialStatus> requireStatusesForUpdate(List<BillingBinding> bindings);
    void authorizePreparedForUpdate(PreparedBillingCharge prepared, QualifiedPromotionUse promotion);
}
