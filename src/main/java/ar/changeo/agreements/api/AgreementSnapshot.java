package ar.changeo.agreements.api;

import ar.changeo.files.api.FileReference;
import ar.changeo.moderation.api.*;
import java.util.*;

public record AgreementSnapshot(ContentReference reference, UUID jobId, String kind, AuthorContext author,
        AgreementTerms terms, String declaredLabel, String digest, List<FileReference> attachments, long version,
        boolean current, boolean approved) {
    public AgreementSnapshot { attachments = List.copyOf(attachments); }
}
