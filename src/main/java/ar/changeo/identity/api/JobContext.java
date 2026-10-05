package ar.changeo.identity.api;

import java.util.Objects;
import java.util.UUID;

public record JobContext(UUID jobId, FulfillmentMode fulfillmentMode) {
    public JobContext {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(fulfillmentMode);
    }
    public enum FulfillmentMode { LOCAL, REMOTE, SHIPPED }
}
