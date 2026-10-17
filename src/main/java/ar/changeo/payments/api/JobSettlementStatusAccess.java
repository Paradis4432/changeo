package ar.changeo.payments.api;

import ar.changeo.moderation.api.ContentReference;
import java.util.UUID;

public interface JobSettlementStatusAccess {
    JobSettlementStatus currentForUpdate(UUID jobId, ContentReference agreement, UUID settlementId);
}
