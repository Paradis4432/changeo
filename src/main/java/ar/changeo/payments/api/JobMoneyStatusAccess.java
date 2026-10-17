package ar.changeo.payments.api;

import ar.changeo.moderation.api.ContentReference;
import java.util.UUID;

public interface JobMoneyStatusAccess {
    JobMoneyStatus currentForUpdate(UUID jobId, ContentReference agreement);
}
