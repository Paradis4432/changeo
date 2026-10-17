package ar.changeo.payments.api;

import ar.changeo.jobs.api.FinancialAgreement;
import java.util.*;

public interface JobLoyaltyMoneyAccess {
    LoyaltyMoneyDiscovery discover(List<FinancialAgreement> agreements);
    Map<UUID, JobLoyaltyMoneyFacts> requireForUpdate(List<FinancialAgreement> agreements, LoyaltyMoneyDiscovery discovery);
}
