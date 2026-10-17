package ar.changeo.subscriptions.api;

import ar.changeo.payments.api.PreparedBillingCharge;

public interface BillingDispatchAccess {
    void authorize(PreparedBillingCharge prepared);
}
