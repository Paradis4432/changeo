package ar.changeo.jobs.api;

import ar.changeo.identity.api.SubjectId;
import java.util.Optional;

public interface JobReputationAccess {
    ReputationPage project(SubjectId subject, Optional<ReputationCursor> cursor);
}
