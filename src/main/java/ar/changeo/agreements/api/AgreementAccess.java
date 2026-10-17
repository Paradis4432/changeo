package ar.changeo.agreements.api;

import ar.changeo.moderation.api.ContentReference;

public interface AgreementAccess {
    AgreementSnapshot discover(ContentReference reference);
    AgreementSnapshot lock(ContentReference reference);
    void requireApproved(AgreementSnapshot snapshot);
}
